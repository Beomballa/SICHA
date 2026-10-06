package com.reasoning.common.story.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.auth.service.AdminSessionVerifier;
import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.auth.service.CryptoService;
import com.reasoning.common.story.entity.QStory;
import com.reasoning.common.story.entity.QStoryAccess;
import com.reasoning.common.story.entity.QStoryVersion;

import jakarta.persistence.EntityManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 사건·자식 콘텐츠·검수 조회/요청의 공통 부모 잠금·감사 경계이며 민감 응답을 현재 권한으로 검증한다. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StoryService {
    private static final String POLICY = "RULE_20260924";
    private static final Set<String> BASIC =
            Set.of(
                    "title",
                    "intro",
                    "setting",
                    "difficulty",
                    "estMin",
                    "estMax",
                    "limitSec",
                    "timelineOrigin");
    private static final Set<String> ANSWER =
            Set.of("culpritCode", "methodAnswer", "timeAnswer", "motiveAnswer");
    private static final Set<String> REVEAL = Set.of("revealText");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TransactionTemplate batchTx;
    private final AdminSessionVerifier sessions;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final JPAQueryFactory queries;

    /** 일반 사건 거래와 별도 소유 READ COMMITTED BATCH 거래를 같은 관리자·세션 저장소에 조립한다. */
    public StoryService(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            AdminSessionVerifier sessions,
            CryptoService crypto,
            ObjectMapper json,
            EntityManager em) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.batchTx = new TransactionTemplate(manager);
        this.batchTx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.batchTx.setReadOnly(false);
        this.sessions = sessions;
        this.crypto = crypto;
        this.json = json;
        this.queries = new JPAQueryFactory(em);
    }

    /**
     * 새 v4 생성 의도에서 변경 불가능한 ST_ 코드를 만들고 DRAFT와 필수 감사를 함께 확정한다.
     *
     * @param sid 행위자에게 결속된 현재 Spring Session ID이며 null이 아니다
     * @param actor 서버에서 발급한 행위자이며 잠금 아래에서 CREATE 권한을 재검사한다
     * @param createKey 재사용할 수 없는 v4 UUID이며 중복 시 응답을 재생하지 않고 충돌한다
     * @param title 공백만으로 이루어지지 않은 사건명이며 최대 160 유니코드 코드포인트다
     * @param requestId 접근 이력과 연결되는 null이 아닌 서버 요청 ID
     * @return 새 사건·버전 식별자와 초기 수정번호
     * @throws AuthException 입력 오류, 권한 회수, 중복 생성 의도 또는 감사 장애 발생 시
     */
    public StoryCreated createStory(
            String sid, AdminActor actor, UUID createKey, String title, UUID requestId) {
        if (createKey == null || createKey.version() != 4 || requestId == null)
            throw AuthException.badRequest("INVALID_REQUEST");
        String value = text(title, 160, false);
        precheck(sid, actor);
        String code =
                "ST_" + createKey.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return transact(
                () -> {
                    Account account = authorizeLocked(sid, actor);
                    if (!account.create) throw AuthException.forbidden("FORBIDDEN");
                    Long storyId =
                            db.queryForObject(
                                    "INSERT INTO story(code,owner_id) VALUES (?,?) RETURNING id",
                                    Long.class,
                                    code,
                                    actor.accountId());
                    Long versionId =
                            db.queryForObject(
                                    "INSERT INTO"
                                        + " story_version(story_id,version_no,status,title,policy_code,created_by,updated_by)"
                                        + " VALUES (?,1,'DRAFT',?,?,?,?) RETURNING id",
                                    Long.class,
                                    storyId,
                                    value,
                                    POLICY,
                                    actor.accountId(),
                                    actor.accountId());
                    audit(
                            storyId,
                            versionId,
                            actor,
                            "STORY_CREATED",
                            null,
                            0L,
                            requestId,
                            "CONTENT",
                            null);
                    return new StoryCreated(code, "0", 1, "0", "DRAFT", requestId);
                },
                "CREATE_CONFLICT");
    }

    /**
     * 한 SQL 스냅샷에서 권한과 대표 버전 조건을 먼저 적용한 뒤 ID 역순 페이지를 읽는다.
     *
     * @param sid 행위자에게 결속된 현재 Spring Session ID이며 null이 아니다
     * @param actor 잠금 아래에서 현재 권한을 검사할 서버 행위자
     * @param size 1~100의 페이지 크기이며 null이면 20이다
     * @param afterId 선택적인 양의 십진수 배타 커서
     * @param code 선택적인 정확 일치 사건 코드
     * @param activeYn null 또는 true이면 활성 사건, false이면 소유자의 비활성 사건만 조회한다
     * @return 허용된 목록과 다음 커서 및 추가 페이지 유무
     * @throws AuthException 필터 오류, 권한 만료 또는 저장소 장애 발생 시
     */
    public StoryPage getStoryList(
            String sid,
            AdminActor actor,
            Integer size,
            String afterId,
            String code,
            Boolean activeYn) {
        int count = size == null ? 20 : size;
        if (count < 1 || count > 100 || code != null && !code.matches("[A-Z0-9_]{1,40}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        Long cursor = afterId == null ? null : positive(afterId);
        precheck(sid, actor);
        return transact(
                () -> {
                    Account a = authorizeLocked(sid, actor);
                    QStory s = QStory.story;
                    QStoryVersion v = QStoryVersion.storyVersion;
                    QStoryVersion work = new QStoryVersion("work");
                    QStoryAccess access = QStoryAccess.storyAccess;
                    boolean active = activeYn == null || activeYn;
                    List<String> permissions = new ArrayList<>(List.of("EDIT"));
                    if (a.review) permissions.add("REVIEW");
                    if (a.publish) permissions.add("PUBLISH");
                    var allowed =
                            s.ownerId
                                    .eq(actor.accountId())
                                    .or(
                                            JPAExpressions.selectOne()
                                                    .from(access)
                                                    .where(
                                                            access.storyId.eq(s.id),
                                                            access.adminId.eq(actor.accountId()),
                                                            access.activeYn.isTrue(),
                                                            access.permission.in(permissions))
                                                    .exists());
                    var workVersion =
                            v.status
                                    .in("DRAFT", "REVIEW", "READY")
                                    .and(v.activeYn.isTrue().or(s.ownerId.eq(actor.accountId())));
                    var fallback =
                            v.id.eq(s.publishedId)
                                    .and(v.activeYn.isTrue())
                                    .and(
                                            JPAExpressions.selectOne()
                                                    .from(work)
                                                    .where(
                                                            work.storyId.eq(s.id),
                                                            work.status.in(
                                                                    "DRAFT", "REVIEW", "READY"),
                                                            work.activeYn
                                                                    .isTrue()
                                                                    .or(
                                                                            s.ownerId.eq(
                                                                                    actor
                                                                                            .accountId())))
                                                    .notExists());
                    var rows =
                            queries.select(
                                            s.id,
                                            s.code,
                                            s.editRev,
                                            s.activeYn,
                                            s.ownerId,
                                            v.versionNo,
                                            v.editRev,
                                            v.status,
                                            v.activeYn,
                                            v.title,
                                            v.difficulty,
                                            v.updatedAt)
                                    .from(s, v)
                                    .where(
                                            v.storyId.eq(s.id),
                                            s.activeYn.eq(active),
                                            active ? allowed : s.ownerId.eq(actor.accountId()),
                                            workVersion.or(fallback),
                                            cursor == null ? null : s.id.lt(cursor),
                                            code == null ? null : s.code.eq(code))
                                    .orderBy(s.id.desc())
                                    .limit(count + 1L)
                                    .fetch();
                    boolean hasNext = rows.size() > count;
                    List<StorySummary> items = new ArrayList<>();
                    for (Tuple row : rows.subList(0, Math.min(rows.size(), count))) {
                        UUID owner =
                                db.queryForObject(
                                        "SELECT account_key FROM admin_account WHERE id=?",
                                        UUID.class,
                                        row.get(s.ownerId));
                        items.add(
                                new StorySummary(
                                        row.get(s.code),
                                        Long.toString(row.get(s.editRev)),
                                        row.get(s.activeYn),
                                        owner,
                                        row.get(v.versionNo),
                                        Long.toString(row.get(v.editRev)),
                                        row.get(v.status),
                                        row.get(v.activeYn),
                                        row.get(v.title),
                                        row.get(v.difficulty),
                                        row.get(v.updatedAt)));
                    }
                    return new StoryPage(
                            items,
                            hasNext,
                            hasNext ? rows.get(count - 1).get(s.id).toString() : null);
                },
                null);
    }

    /**
     * 계정→자격→세션→사건→버전을 잠그고 CONTENT_READ 감사를 확정한 뒤 원고를 반환한다.
     *
     * @param sid 행위자에게 결속된 현재 Spring Session ID이며 null이 아니다
     * @param actor 현재 소유자 또는 허용된 협업자 권한을 검사할 서버 행위자
     * @param storyCode 변경 불가능한 정확한 사건 코드
     * @param versionNo 해당 사건의 양의 버전 번호
     * @param requestId 감사에 기록할 null이 아닌 서버 접근 이력 요청 ID
     * @return 저장된 영역, 현재 행동 권한, 서버 정책 투영과 차단하지 않는 경고
     * @throws AuthException 접근 불가·대상 없음·권한 만료 또는 필수 조회 감사 실패 시
     */
    public VersionDetail getStoryDetail(
            String sid, AdminActor actor, String storyCode, int versionNo, UUID requestId) {
        if (requestId == null) throw AuthException.badRequest("INVALID_REQUEST");
        path(storyCode, versionNo);
        precheck(sid, actor);
        return transact(
                () -> {
                    LockedVersion locked = lockVersion(sid, actor, storyCode, versionNo, null);
                    StoryRow story = locked.story;
                    VersionRow v = locked.version;
                    audit(
                            story.id,
                            v.id,
                            actor,
                            "CONTENT_READ",
                            v.rev,
                            v.rev,
                            requestId,
                            "CONTENT",
                            null);
                    return detail(story, v, locked.account);
                },
                null);
    }

    /**
     * 편집 HTML을 반환하기 전에 원고를 읽지 않고 대상 버전의 현재 조회 자격만 검사한다.
     *
     * @param sid 현재 일반 세션 ID이며 null이면 인증을 거절한다
     * @param actor 서버가 확인한 행위자
     * @param storyCode 정확한 사건 코드
     * @param versionNo 양의 버전 번호
     * @throws AuthException 인증 만료, 대상 비노출 또는 저장소 장애 시
     */
    public void checkStoryAccess(String sid, AdminActor actor, String storyCode, int versionNo) {
        path(storyCode, versionNo);
        precheck(sid, actor);
        transact(
                () -> {
                    Account account = authorizeLocked(sid, actor);
                    StoryRow story = story(storyCode);
                    permit(story, account, actor, false);
                    Boolean active =
                            db.query(
                                    "SELECT active_yn FROM story_version WHERE story_id=? AND"
                                            + " version_no=? FOR UPDATE",
                                    rs -> rs.next() ? rs.getBoolean(1) : null,
                                    story.id,
                                    versionNo);
                    if (active == null || !active && story.owner != actor.accountId())
                        throw missing();
                    return null;
                },
                null);
    }

    /**
     * DRAFT의 한 영역만 수정번호 확인과 필수 감사를 포함한 동일 트랜잭션에서 갱신한다.
     *
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
    public ContentResult updateStorySection(
            String sid,
            AdminActor actor,
            String storyCode,
            int versionNo,
            String section,
            String expectedRev,
            JsonNode changes,
            UUID requestId) {
        path(storyCode, versionNo);
        Set<String> allowed =
                switch (section == null ? "" : section) {
                    case "basic" -> BASIC;
                    case "answer" -> ANSWER;
                    case "reveal" -> REVEAL;
                    default -> throw AuthException.badRequest("INVALID_REQUEST");
                };
        if (requestId == null || changes == null || !changes.isObject() || changes.isEmpty())
            throw AuthException.badRequest("INVALID_REQUEST");
        changes.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!allowed.contains(field))
                                throw AuthException.badRequest("INVALID_REQUEST");
                        });
        long expected = revision(expectedRev);
        precheck(sid, actor);
        return transact(
                () -> {
                    LockedVersion locked = lockVersion(sid, actor, storyCode, versionNo, expected);
                    StoryRow story = locked.story;
                    VersionRow v = locked.version;
                    Map<String, Object> old = fields(v);
                    Map<String, Object> next = new LinkedHashMap<>(old);
                    changes.properties()
                            .forEach(
                                    entry ->
                                            next.put(
                                                    entry.getKey(),
                                                    fieldValue(entry.getKey(), entry.getValue())));
                    Short min = (Short) next.get("estMin"), max = (Short) next.get("estMax");
                    if (min != null && max != null && max < min)
                        throw AuthException.unprocessable("INVALID_INPUT");
                    String culprit = (String) next.get("culpritCode");
                    if (culprit != null
                            && db.queryForObject(
                                            "SELECT count(*) FROM story_person WHERE version_id=?"
                                                    + " AND code=? AND active_yn",
                                            Integer.class,
                                            v.id,
                                            culprit)
                                    == 0) throw AuthException.unprocessable("INVALID_INPUT");
                    if (old.equals(next))
                        return new ContentResult(
                                storyCode,
                                versionNo,
                                Long.toString(v.rev),
                                false,
                                warningsWithClues(v),
                                requestId);
                    if (v.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
                    db.update(
                            "UPDATE grade_sample SET checked_by=NULL WHERE version_id=? AND"
                                    + " checked_by IS NOT NULL",
                            v.id);
                    db.update(
                            "UPDATE story_version SET"
                                + " title=?,intro=?,setting=?,difficulty=?,est_min=?,est_max=?,limit_sec=?,timeline_origin=?,culprit_code=?,method_answer=?,time_answer=?,motive_answer=?,reveal_text=?,edit_rev=edit_rev+1,updated_by=?,updated_at=clock_timestamp()"
                                + " WHERE id=?",
                            next.get("title"),
                            next.get("intro"),
                            next.get("setting"),
                            next.get("difficulty"),
                            next.get("estMin"),
                            next.get("estMax"),
                            next.get("limitSec"),
                            next.get("timelineOrigin"),
                            next.get("culpritCode"),
                            next.get("methodAnswer"),
                            next.get("timeAnswer"),
                            next.get("motiveAnswer"),
                            next.get("revealText"),
                            actor.accountId(),
                            v.id);
                    audit(
                            story.id,
                            v.id,
                            actor,
                            "SECTION_UPDATED",
                            v.rev,
                            v.rev + 1,
                            requestId,
                            "CONTENT",
                            changes.properties().stream().map(Map.Entry::getKey).toList());
                    return new ContentResult(
                            storyCode,
                            versionNo,
                            Long.toString(v.rev + 1),
                            true,
                            warningsWithClues(version(story.id, versionNo)),
                            requestId);
                },
                null);
    }

    /**
     * 최초 작업 초안만 소유자의 최근 재인증과 두 수정번호 아래 논리 삭제·복원한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버가 검증한 현재 사건 소유자
     * @param storyCode 변경 불가능한 사건 코드
     * @param expectedStoryRev 현재 사건 관계 수정번호 문자열
     * @param expectedRev 현재 버전 1의 콘텐츠 수정번호 문자열
     * @param active true면 복원, false면 논리 삭제
     * @param reasonCode 삭제는 DRAFT_WITHDRAWN, 복원은 WORK_RESUMED
     * @param verificationRef 비개인 확인 참조인 ASCII 8~64자
     * @param requestId 필수 감사 요청 ID
     * @return 원고 없는 사건 상태·수정번호·감사 확정 상태
     * @throws AuthException 권한·재인증·수명주기·수정번호·감사 실패 시
     */
    public StoryStateResult updateStoryActive(
            String sid,
            AdminActor actor,
            String storyCode,
            String expectedStoryRev,
            String expectedRev,
            boolean active,
            String reasonCode,
            String verificationRef,
            UUID requestId) {
        path(storyCode, 1);
        long storyRev = revision(expectedStoryRev);
        long contentRev = revision(expectedRev);
        if (requestId == null
                || !(active ? "WORK_RESUMED" : "DRAFT_WITHDRAWN").equals(reasonCode)
                || verificationRef == null
                || !verificationRef.matches("[A-Za-z0-9_-]{8,64}"))
            throw AuthException.badRequest("INVALID_REQUEST");
        precheck(sid, actor);
        return transact(
                () -> {
                    Account account = authorizeLocked(sid, actor);
                    StoryRow story = story(storyCode);
                    permit(story, account, actor, false);
                    if (story.owner != actor.accountId())
                        throw AuthException.forbidden("FORBIDDEN");
                    recentReauth(actor);
                    VersionRow version = version(story.id, 1);
                    if (story.rev != storyRev || version.rev != contentRev)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    boolean eligible =
                            story.published == null
                                    && version.active
                                    && version.snapshot == null
                                    && "DRAFT".equals(version.status)
                                    && !db.queryForObject(
                                            "SELECT view_yn FROM story WHERE id=?",
                                            Boolean.class,
                                            story.id)
                                    && db.queryForObject(
                                                    "SELECT count(*) FROM story_version WHERE"
                                                            + " story_id=?",
                                                    Integer.class,
                                                    story.id)
                                            == 1
                                    && db.queryForObject(
                                                    "SELECT count(*) FROM review_snapshot WHERE"
                                                            + " version_id=?",
                                                    Integer.class,
                                                    version.id)
                                            == 0;
                    if (!eligible) throw AuthException.conflict("STATE_CONFLICT");
                    if (story.active == active)
                        return new StoryStateResult(
                                storyCode,
                                Long.toString(story.rev),
                                active,
                                false,
                                "NOT_REQUIRED",
                                requestId);
                    if (story.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
                    db.update(
                            "UPDATE story SET"
                                + " active_yn=?,view_yn=false,edit_rev=edit_rev+1,updated_at=clock_timestamp()"
                                + " WHERE id=?",
                            active,
                            story.id);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("requestId", requestId);
                    detail.put("revisionScope", "STORY");
                    detail.put("resource", "story");
                    detail.put("reasonCode", reasonCode);
                    detail.put("verificationRef", verificationRef);
                    detail.put("before", Map.of("activeYn", story.active));
                    detail.put("after", Map.of("activeYn", active));
                    insertAudit(
                            story.id,
                            version.id,
                            actor,
                            active ? "STORY_REACTIVATED" : "STORY_DEACTIVATED",
                            story.rev,
                            story.rev + 1,
                            detail);
                    return new StoryStateResult(
                            storyCode,
                            Long.toString(story.rev + 1),
                            active,
                            true,
                            "RECORDED",
                            requestId);
                },
                null);
    }

    /**
     * 사건 관계의 행위자·대상 계정을 ID 정순으로 잠근 뒤 현재 권한·재인증·사건 수정번호를 재검사한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param code 대상 사건 코드
     * @param targetKey 관계 변경 대상의 공개 UUID 또는 목록에서 null
     * @param expectedStoryRev 변경 시 십진 사건 수정번호, 목록은 null
     * @param ownerOnly EDIT 변경에는 true, REVIEW/PUBLISH 변경에는 false, 목록에는 null
     * @param work 잠금 중 실행할 짧은 DB 동작이며 원격 호출·사용자 대기는 허용하지 않는다
     * @return 현재 권한으로 확정된 목록 또는 관계 영수증
     */
    <T> T withStoryAccess(
            String sid,
            AdminActor actor,
            String code,
            UUID targetKey,
            String expectedStoryRev,
            Boolean ownerOnly,
            java.util.function.Function<AccessScope, T> work) {
        path(code, 1);
        Long expected = expectedStoryRev == null ? null : revision(expectedStoryRev);
        precheck(sid, actor);
        return transact(
                () -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    Long targetId =
                            targetKey == null
                                    ? null
                                    : db.query(
                                            "SELECT id FROM admin_account WHERE account_key=?",
                                            rs -> rs.next() ? rs.getLong(1) : null,
                                            targetKey);
                    List<Long> ids = new ArrayList<>(List.of(actor.accountId()));
                    if (targetId != null && targetId != actor.accountId()) ids.add(targetId);
                    lockAccounts(ids);
                    authorizeLocked(sid, actor);
                    StoryRow story = story(code);
                    boolean owner = story.owner == actor.accountId();
                    boolean manager =
                            Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "SELECT can_manage FROM admin_account WHERE id=?",
                                            Boolean.class,
                                            actor.accountId()));
                    if (!owner && !manager) throw missing();
                    if (ownerOnly != null && (ownerOnly && !owner || !ownerOnly && !manager))
                        throw AuthException.forbidden("FORBIDDEN");
                    recentReauth(actor);
                    if (expected != null && story.rev != expected)
                        throw AuthException.conflict("EDIT_CONFLICT");
                    if (targetKey != null && targetId == null) throw missing();
                    UUID ownerKey =
                            db.queryForObject(
                                    "SELECT account_key FROM admin_account WHERE id=?",
                                    UUID.class,
                                    story.owner);
                    return work.apply(
                            new AccessScope(
                                    story.id,
                                    story.rev,
                                    story.active,
                                    story.owner,
                                    ownerKey,
                                    targetId));
                },
                null);
    }

    /**
     * 소유자·수신자·행위자의 현재 계정을 사건보다 먼저 잠그고 인계 상태·영수증 작업을 보호한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 서버 검증 행위자
     * @param code 대상 사건 코드
     * @param recipientKey 잠금 전에 확인한 수신자 공개 UUID 또는 null
     * @param work 현재 owner/recipient ID와 사건 수정번호를 대조할 짧은 DB 작업
     * @return 현재 인증·최근 재인증에서 확인한 최소 조회 또는 인계 결과
     */
    <T> T withOwnership(
            String sid,
            AdminActor actor,
            String code,
            UUID recipientKey,
            java.util.function.Function<OwnerScope, T> work) {
        path(code, 1);
        precheck(sid, actor);
        return transact(
                () -> {
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    Long priorOwner =
                            db.query(
                                    "SELECT owner_id FROM story WHERE code=?",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    code);
                    Long recipient =
                            recipientKey == null
                                    ? null
                                    : db.query(
                                            "SELECT id FROM admin_account WHERE account_key=?",
                                            rs -> rs.next() ? rs.getLong(1) : null,
                                            recipientKey);
                    PendingPart pending =
                            db.query(
                                    "SELECT from_id,to_id FROM story_transfer WHERE"
                                            + " story_id=(SELECT id FROM story WHERE code=?) AND"
                                            + " state='PENDING'",
                                    rs ->
                                            rs.next()
                                                    ? new PendingPart(rs.getLong(1), rs.getLong(2))
                                                    : null,
                                    code);
                    List<Long> ids = new ArrayList<>(List.of(actor.accountId()));
                    if (priorOwner != null) ids.add(priorOwner);
                    if (recipient != null) ids.add(recipient);
                    if (pending != null) {
                        ids.add(pending.fromId());
                        ids.add(pending.toId());
                    }
                    lockAccounts(ids);
                    authorizeLocked(sid, actor);
                    StoryRow story = story(code);
                    if (priorOwner == null || priorOwner != story.owner)
                        throw AuthException.conflict("TRANSFER_INVALIDATED");
                    PendingPart currentPending =
                            db.query(
                                    "SELECT from_id,to_id FROM story_transfer WHERE story_id=? AND"
                                            + " state='PENDING'",
                                    rs ->
                                            rs.next()
                                                    ? new PendingPart(rs.getLong(1), rs.getLong(2))
                                                    : null,
                                    story.id);
                    if (!java.util.Objects.equals(pending, currentPending))
                        throw AuthException.conflict("TRANSFER_INVALIDATED");
                    recentReauth(actor);
                    UUID ownerKey =
                            db.queryForObject(
                                    "SELECT account_key FROM admin_account WHERE id=?",
                                    UUID.class,
                                    story.owner);
                    return work.apply(
                            new OwnerScope(
                                    story.id,
                                    story.owner,
                                    ownerKey,
                                    story.rev,
                                    story.active,
                                    recipient,
                                    pending == null ? null : pending.toId()));
                },
                null);
    }

    /** 관리자 계정→등록→자격증명의 고정 잠금 순서를 사건 관리·인계가 공유한다. */
    private void lockAccounts(List<Long> candidates) {
        List<Long> ids = candidates.stream().distinct().sorted().toList();
        for (long id : ids)
            db.queryForObject("SELECT id FROM admin_account WHERE id=? FOR UPDATE", Long.class, id);
        for (long id : ids)
            db.query(
                    "SELECT id FROM admin_enrollment WHERE account_id=? AND completed_at IS NULL"
                            + " FOR UPDATE",
                    rs -> {
                        while (rs.next()) {}
                        return null;
                    },
                    id);
        for (long id : ids)
            db.query(
                    "SELECT account_id FROM admin_credential WHERE account_id=? FOR UPDATE",
                    rs -> {
                        while (rs.next()) {}
                        return null;
                    },
                    id);
    }

    /** 최근 5분 재인증은 세션의 현재 DB 시각으로 검사한다. */
    private void recentReauth(AdminActor actor) {
        Boolean recent =
                db.queryForObject(
                        "SELECT reauth_at<=clock_timestamp() AND"
                            + " clock_timestamp()<reauth_at+interval '5 minutes' FROM admin_session"
                            + " WHERE session_key=? AND account_id=? AND auth_rev=? AND"
                            + " state='ACTIVE'",
                        Boolean.class,
                        actor.sessionKey(),
                        actor.accountId(),
                        actor.authRev());
        if (!Boolean.TRUE.equals(recent)) throw AuthException.forbidden("REAUTH_REQUIRED");
    }

    /**
     * 관계 실변경만 storyRev를 증가시키며 감사 실패 원인을 회수 전용 재시도에 구분해 전달한다.
     *
     * @param scope 잠금 중인 사건·관계 수정번호
     * @param actor 현재 행위자
     * @param targetKey 대상 계정 공개 UUID
     * @param permission EDIT, REVIEW 또는 PUBLISH
     * @param before 변경 전 활성 여부
     * @param after 변경 후 활성 여부
     * @param reason 고정된 업무 사유 코드
     * @param ref 비개인 확인 참조
     * @param id 접근 이력과 연결한 요청 ID
     * @param audited false는 첫 감사 실패 뒤의 회수 전용 독립 TX에서만 사용한다
     * @return 확정될 새 사건 수정번호
     */
    long recordAccessChange(
            AccessScope scope,
            AdminActor actor,
            UUID targetKey,
            String permission,
            boolean before,
            boolean after,
            String reason,
            String ref,
            UUID id,
            boolean audited) {
        if (scope.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
        db.update(
                "UPDATE story SET edit_rev=edit_rev+1,updated_at=clock_timestamp() WHERE id=?",
                scope.storyId);
        if (audited) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requestId", id);
            detail.put("revisionScope", "STORY");
            detail.put("resource", "access");
            detail.put("itemKey", targetKey + "~" + permission);
            detail.put("reasonCode", reason);
            detail.put("verificationRef", ref);
            detail.put("before", Map.of("activeYn", before, "permission", permission));
            detail.put("after", Map.of("activeYn", after, "permission", permission));
            try {
                insertAudit(
                        scope.storyId,
                        null,
                        actor,
                        after ? "ACCESS_GRANTED" : "ACCESS_REVOKED",
                        scope.rev,
                        scope.rev + 1,
                        detail);
            } catch (DataAccessException failure) {
                throw new AccessAuditFailure(failure);
            }
        }
        return scope.rev + 1;
    }

    /** 승인된 인계 행동만 기존 4 KiB 필수 감사에 연결한다. 시스템 정비는 행위자를 NULL로 둔다. */
    void auditOwnership(
            OwnerScope scope,
            AdminActor actor,
            String action,
            long before,
            long after,
            Map<String, Object> detail) {
        boolean system = Set.of("OWNER_EXPIRED", "OWNER_INVALIDATED").contains(action);
        if (system != (actor == null)
                || !Set.of(
                                "OWNER_REQUESTED",
                                "OWNER_ACCEPTED",
                                "OWNER_CANCELLED",
                                "OWNER_DECLINED",
                                "OWNER_EXPIRED",
                                "OWNER_INVALIDATED",
                                "OWNER_OVERRIDDEN")
                        .contains(action)) throw AuthException.badRequest("INVALID_REQUEST");
        insertAudit(scope.storyId(), null, actor, action, before, after, detail);
    }

    /** 필수 업무 감사 실패만 보안 축소의 독립 차단 재검사로 연결한다. */
    static final class AccessAuditFailure extends RuntimeException {
        AccessAuditFailure(DataAccessException cause) {
            super(cause);
        }
    }

    /**
     * 자식 서비스가 동일한 인증→사건→버전 잠금과 오류 변환 안에서만 동작하도록 경계를 제공한다.
     *
     * @param sid 현재 일반 세션 ID이며 null이면 인증을 거절한다
     * @param actor 서버가 확인한 현재 행위자
     * @param code 정확한 사건 코드
     * @param number 양의 버전 번호
     * @param expectedRev null이면 조회, 아니면 음수 없는 문자열 수정번호를 확인하는 편집이다
     * @param work 보호된 같은 트랜잭션에서 수행할 동기 DB 작업이며 네트워크·사용자 대기를 해서는 안 된다
     * @return 작업의 원문 없는 영수증 또는 인가된 조회 값
     */
    <T> T withVersion(
            String sid,
            AdminActor actor,
            String code,
            int number,
            String expectedRev,
            java.util.function.Function<VersionScope, T> work) {
        path(code, number);
        Long expected = expectedRev == null ? null : revision(expectedRev);
        precheck(sid, actor);
        return transact(
                () -> {
                    LockedVersion locked = lockVersion(sid, actor, code, number, expected);
                    VersionRow v = locked.version;
                    return work.apply(
                            new VersionScope(
                                    locked.story.id, v.id, v.rev, v.culprit, warningsWithClues(v)));
                },
                null);
    }

    /**
     * 기존 제작 자료 조회권으로 현재 자격·세션·부모를 잠그며 편집 자격을 추가하지 않는다.
     *
     * @param sid null이 아닌 저장 세션 ID
     * @param actor 현재 자격을 다시 검사할 서버 행위자
     * @param code null이 아닌 사건 코드
     * @param number 양의 버전 번호
     * @param work 같은 거래의 조회·필수 감사 작업이며 외부 호출은 금지한다
     * @return 현재 부모 접근권으로 구성한 조회 결과
     * @throws AuthException 현재 인증·접근권·잠금·저장 실패 시
     */
    <T> T withReviewRead(
            String sid,
            AdminActor actor,
            String code,
            int number,
            java.util.function.Function<ReadScope, T> work) {
        path(code, number);
        precheck(sid, actor);
        return transact(
                () -> {
                    LockedVersion locked = lockVersion(sid, actor, code, number, null);
                    VersionRow v = locked.version;
                    return work.apply(
                            new ReadScope(
                                    locked.story.id,
                                    v.id,
                                    v.rev,
                                    locked.story.active && v.active,
                                    v.status,
                                    v.snapshot,
                                    json.valueToTree(
                                            Map.of(
                                                    "basic", subset(fields(v), BASIC),
                                                    "answer", subset(fields(v), ANSWER),
                                                    "reveal", subset(fields(v), REVEAL)))));
                },
                null);
    }

    /**
     * 현재 편집 인가와 부모 잠금만 확정한다. 재전송 조회가 신규 DRAFT 검사보다 먼저 실행되도록 한다.
     *
     * @param sid null이 아닌 현재 세션 ID
     * @param actor 현재 계정·자격·세션을 다시 검사할 서버 행위자
     * @param code 저장된 사건 코드
     * @param number 양의 버전 번호
     * @param expectedRev null이 아닌 정규 십진 수정번호
     * @param work 같은 거래에서만 사용할 검수 작업이며 네트워크 호출은 금지한다
     * @return 잠금 안에서 작성한 원문 없는 결과
     * @throws AuthException 현재 인가·입력·잠금·필수 감사 실패 시
     */
    <T> T withReviewVersion(
            String sid,
            AdminActor actor,
            String code,
            int number,
            String expectedRev,
            java.util.function.Function<ReviewScope, T> work) {
        path(code, number);
        long expected = revision(expectedRev);
        precheck(sid, actor);
        return transact(
                () -> {
                    Account account = authorizeLocked(sid, actor);
                    StoryRow story = story(code);
                    // 조회 권한만으로 요청을 재생할 수 없으며 현재 EDIT를 별도로 확인한다.
                    permit(story, account, actor, false);
                    VersionRow v = version(story.id, number);
                    if (!v.active && story.owner != actor.accountId()) throw missing();
                    permit(story, account, actor, true);
                    return work.apply(
                            new ReviewScope(
                                    story.id,
                                    v.id,
                                    v.rev,
                                    expected,
                                    story.active && v.active,
                                    v.status,
                                    v.snapshot,
                                    v.policy,
                                    json.valueToTree(
                                            Map.of(
                                                    "basic",
                                                    subset(fields(v), BASIC),
                                                    "answer",
                                                    subset(fields(v), ANSWER),
                                                    "reveal",
                                                    subset(fields(v), REVEAL)))));
                },
                null);
    }

    /**
     * 신규 키만 활성 DRAFT·수정번호·빈 현재 사본을 검사한다.
     *
     * @param scope 현재 잠금에서 읽은 null이 아닌 검수 문맥
     * @throws AuthException 단계·활성 불일치는 STATE_CONFLICT, 수정번호 불일치는 EDIT_CONFLICT
     */
    void requireReviewDraft(ReviewScope scope) {
        if (!scope.active || !"DRAFT".equals(scope.status) || scope.snapshotId != null)
            throw AuthException.conflict("STATE_CONFLICT");
        if (scope.rev != scope.expected) throw AuthException.conflict("EDIT_CONFLICT");
    }

    /**
     * 부모 잠금으로 직렬화된 전체 활성 원본을 읽으며 관계의 불일치 행을 조인으로 숨기지 않는다.
     *
     * @param scope 현재 거래의 검수 부모 잠금이며 null이 아니다
     * @return 열한 배열과 역사적 checker 참조가 보존된 서버 전용 원본
     * @throws AuthException JSON 저장값을 정확하게 해독할 수 없으면 저장소 오류
     */
    ReviewSource reviewSource(ReviewScope scope) {
        var resources = json.createObjectNode();
        sourceRows(
                resources,
                "persons",
                "story_person",
                "'code',code,'name',name,'publicText',public_text,'secretText',secret_text",
                scope.versionId);
        sourceRows(
                resources,
                "roles",
                "story_role",
                "'code',code,'name',name,'brief',brief",
                scope.versionId);
        sourceRows(
                resources, "pairs", "story_pair", "'roleA',role_a,'roleB',role_b", scope.versionId);
        sourceRows(
                resources,
                "clues",
                "story_clue",
                "'code',code,'title',title,'body',body,'personCode',person_code,'scope',scope,'sourceText',source_text",
                scope.versionId);
        sourceRows(
                resources,
                "clueRoles",
                "clue_role",
                "'clueCode',clue_code,'roleCode',role_code",
                scope.versionId);
        sourceRows(
                resources,
                "hints",
                "story_hint",
                "'code',code,'level',level,'body',body",
                scope.versionId);
        sourceRows(
                resources,
                "events",
                "story_event",
                "'code',code,'startMin',start_min,'endMin',end_min,'actualText',actual_text,'apparentText',apparent_text",
                scope.versionId);
        sourceRows(
                resources,
                "facts",
                "story_fact",
                "'code',code,'statement',statement,'truth',truth,'basis',basis",
                scope.versionId);
        sourceRows(
                resources,
                "rubrics",
                "story_rubric",
                "'code',code,'category',category,'maxScore',max_score,'requiredYn',required_yn,'passScore',pass_score,'acceptedText',accepted_text,'partialText',partial_text,'rejectText',reject_text,'ruleData',rule_data",
                scope.versionId);
        sourceRows(
                resources,
                "rubricClues",
                "rubric_clue",
                "'rubricCode',rubric_code,'clueCode',clue_code,'linkText',link_text",
                scope.versionId);
        var samples = resources.putArray("gradeSamples");
        List<String> unresolved = new ArrayList<>();
        db.query(
                "SELECT"
                    + " jsonb_build_object('code',g.code,'inputData',g.input_data,'expectData',g.expect_data,'expectedScore',g.expected_score,'expectedSuccess',g.expected_success,'reason',g.reason,'checkedBy',a.account_key)::text,g.checked_by,a.account_key,g.code"
                    + " FROM grade_sample g LEFT JOIN admin_account a ON a.id=g.checked_by WHERE"
                    + " g.version_id=? AND g.active_yn",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> {
                            samples.add(sourceJson(rs.getString(1)));
                            if (rs.getObject(2) != null && rs.getObject(3) == null)
                                unresolved.add(rs.getString(4));
                        },
                scope.versionId);
        return new ReviewSource(scope.sections, resources, List.copyOf(unresolved));
    }

    /**
     * 저장된 부분 초안도 정책 생성·완성도 검사 없이 기존 열한 활성 배열 로더로 읽는다.
     *
     * @param scope 같은 거래에서 최신 DRAFT를 확인한 null이 아닌 조회 문맥
     * @return 저장 null과 전체 활성 행을 그대로 보존한 서버 전용 원본
     * @throws AuthException 저장 JSON을 정확하게 해독할 수 없으면 저장소 오류
     */
    ReviewSource previewSource(ReadScope scope) {
        return reviewSource(
                new ReviewScope(
                        scope.storyId,
                        scope.versionId,
                        scope.rev,
                        scope.rev,
                        scope.active,
                        scope.status,
                        scope.snapshotId,
                        null,
                        scope.sections));
    }

    /**
     * payload를 선택하지 않고 같은 버전의 역사적 메타데이터만 배타 커서로 읽는다.
     *
     * @param scope 현재 부모 잠금과 제작 자료 접근권을 가진 문맥
     * @param size 1~100의 요청 수이며 다음 페이지 확인용 한 행을 추가한다
     * @param afterId null 또는 양의 배타 ID 커서
     * @return ID 내림차순의 기록이며 작성자의 현재 활성 상태로 필터하지 않는다
     * @throws AuthException 작성자의 공개 UUID를 해석하지 못하면 저장소 오류
     */
    List<SnapshotRow> snapshotRows(ReadScope scope, int size, Long afterId) {
        return db.query(
                "SELECT s.id,s.edit_rev,s.format_no,s.created_at,a.account_key FROM"
                        + " review_snapshot s LEFT JOIN admin_account a ON a.id=s.created_by"
                        + " WHERE s.version_id=?"
                        + (afterId == null ? "" : " AND s.id<?")
                        + " ORDER BY s.id DESC LIMIT ?",
                (rs, row) -> snapshotRow(rs, scope, false),
                afterId == null
                        ? new Object[] {scope.versionId, size + 1}
                        : new Object[] {scope.versionId, afterId, size + 1});
    }

    /**
     * 같은 부모 버전에 속한 사본만 읽으며 다른 부모의 ID는 비공개 404로 처리한다.
     *
     * @param scope 같은 거래의 현재 제작 자료 접근권과 부모 잠금 문맥
     * @param snapshotId 정규 입력 검증을 통과한 양의 bigint ID
     * @return 작성자 공개 UUID·실제 저장 형식·원래 JSONB와 현재 포인터 일치 여부
     * @throws AuthException 소속 불일치는 404, 작성자·JSON 해석 실패는 저장소 오류
     */
    SnapshotRow snapshot(ReadScope scope, long snapshotId) {
        SnapshotRow result =
                db.query(
                        "SELECT"
                            + " s.id,s.edit_rev,s.format_no,s.created_at,a.account_key,s.payload::text"
                            + " FROM review_snapshot s LEFT JOIN admin_account a ON"
                            + " a.id=s.created_by WHERE s.version_id=? AND s.id=?",
                        rs -> rs.next() ? snapshotRow(rs, scope, true) : null,
                        scope.versionId,
                        snapshotId);
        if (result == null) throw missing();
        return result;
    }

    private SnapshotRow snapshotRow(ResultSet rs, ReadScope scope, boolean payload)
            throws SQLException {
        UUID creator = (UUID) rs.getObject(5);
        if (creator == null) throw AuthException.unavailable("STORY_UNAVAILABLE");
        long snapshotId = rs.getLong(1);
        return new SnapshotRow(
                snapshotId,
                rs.getLong(2),
                rs.getInt(3),
                rs.getTimestamp(4).toInstant(),
                creator,
                scope.snapshotId != null && scope.snapshotId == snapshotId,
                payload ? sourceJson(rs.getString(6)) : null);
    }

    /**
     * 사본·미리보기의 안전한 식별자만 같은 거래의 필수 민감 조회 감사에 기록한다.
     *
     * @param scope 현재 잠금의 null이 아닌 조회 문맥
     * @param actor 현재 제작 자료 접근권을 확인한 행위자
     * @param sourceRev 현재 초안 또는 선택 사본의 원래 수정번호
     * @param snapshotId 초안·목록이면 null, 아니면 선택 사본의 양의 ID
     * @param mode 미리보기 ROLE/REVEAL 또는 사본 조회이면 null
     * @param roleCode ROLE의 검증된 코드 또는 null
     * @param requestId null이 아닌 서버 요청 UUID
     * @throws AuthException 필수 감사 실패 시 응답을 차단하고 거래를 롤백한다
     */
    void recordSnapshotRead(
            ReadScope scope,
            AdminActor actor,
            long sourceRev,
            Long snapshotId,
            String mode,
            String roleCode,
            UUID requestId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("revisionScope", "CONTENT");
        detail.put("requestId", requestId);
        detail.put("sourceRev", Long.toString(sourceRev));
        if (snapshotId != null) detail.put("snapshotId", snapshotId.toString());
        if (mode != null) detail.put("mode", mode);
        if (roleCode != null) detail.put("roleCode", roleCode);
        insertAudit(
                scope.storyId,
                scope.versionId,
                actor,
                "CONTENT_READ",
                scope.rev,
                scope.rev,
                detail);
    }

    /** 테이블·표현식은 위 서버 고정 매핑만 전달하며 사용자 문자열은 SQL 식별자가 될 수 없다. */
    private void sourceRows(
            com.fasterxml.jackson.databind.node.ObjectNode resources,
            String resource,
            String table,
            String fields,
            long versionId) {
        var rows = resources.putArray(resource);
        db.query(
                "SELECT jsonb_build_object("
                        + fields
                        + ")::text FROM "
                        + table
                        + " WHERE version_id=? AND active_yn",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> rows.add(sourceJson(rs.getString(1))),
                versionId);
    }

    private static JsonNode sourceJson(String stored) {
        try {
            return com.reasoning.common.grading.model.SnapshotJson.parse(
                    stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    /**
     * 기존 사본의 요청자·전환 전 수정번호만 읽고 현재 원본을 다시 구성하지 않는다.
     *
     * @param scope 현재 거래의 null이 아닌 부모 잠금
     * @param key null이 아닌 검증된 UUID v4 의도 키
     * @return 기존 불변 식별자 또는 미사용 키이면 null
     */
    ReviewReplay reviewReplay(ReviewScope scope, UUID key) {
        return db.query(
                "SELECT id,edit_rev,created_by FROM review_snapshot WHERE version_id=? AND"
                        + " request_key=?",
                rs ->
                        rs.next()
                                ? new ReviewReplay(rs.getLong(1), rs.getLong(2), rs.getLong(3))
                                : null,
                scope.versionId,
                key);
    }

    /**
     * 원문 없는 사전 검사의 필수 조회 감사를 동일 거래에 기록한다.
     *
     * @param scope 같은 거래의 null이 아닌 부모 잠금
     * @param actor 현재 EDIT 인가를 통과한 행위자
     * @param requestId null이 아닌 서버 연결 UUID
     * @throws AuthException 필수 감사 저장 실패 시 응답을 차단하고 거래를 되돌린다
     */
    void recordReviewRead(ReviewScope scope, AdminActor actor, UUID requestId) {
        audit(
                scope.storyId,
                scope.versionId,
                actor,
                "CONTENT_READ",
                scope.rev,
                scope.rev,
                requestId,
                "CONTENT",
                null);
    }

    /**
     * 검증된 전체 사본·서버 STRUCTURE·전환·감사를 한 거래로 확정하며 checked_by는 보존한다.
     *
     * @param scope 신규 DRAFT와 수정번호 검사를 통과한 현재 부모 잠금
     * @param actor 현재 EDIT 행위자
     * @param key 브라우저의 UUID v4 요청키
     * @param frozen 전체 집합 검증을 통과한 불변 사본
     * @param warningCount 잘리지 않은 전체 경고 수
     * @param requestId null이 아닌 서버 감사 연결 UUID
     * @return 확정할 신규 사본 ID
     * @throws AuthException 수정번호 소진·닫힌 갱신 실패·필수 감사 실패 시 모두 롤백한다
     */
    long recordReviewRequest(
            ReviewScope scope,
            AdminActor actor,
            UUID key,
            com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot frozen,
            int warningCount,
            UUID requestId) {
        if (scope.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
        long snapshot =
                db.queryForObject(
                        "INSERT INTO"
                            + " review_snapshot(version_id,edit_rev,format_no,payload,request_key,created_by)"
                            + " VALUES (?,?,1,?::jsonb,?,?) RETURNING id",
                        Long.class,
                        scope.versionId,
                        scope.rev,
                        new String(frozen.payloadBytes(), java.nio.charset.StandardCharsets.UTF_8),
                        key,
                        actor.accountId());
        boolean self =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM story_version WHERE id=? AND"
                                    + " created_by=?) OR EXISTS(SELECT 1 FROM story_audit WHERE"
                                    + " story_id=? AND version_id=? AND actor_id=? AND action IN"
                                    + " ('STORY_CREATED','SECTION_UPDATED','ITEM_CREATED','ITEM_UPDATED','ITEM_DEACTIVATED','ITEM_REACTIVATED'))",
                                Boolean.class,
                                scope.versionId,
                                actor.accountId(),
                                scope.storyId,
                                scope.versionId,
                                actor.accountId()));
        var evidence = json.createObjectNode().put("formatNo", 1);
        evidence.putObject("request").put("expectedRev", Long.toString(scope.expected));
        evidence.putObject("details")
                .put("ruleVersion", "STORY-REVIEW-01")
                .put("checkedRev", Long.toString(scope.rev))
                .put("errorCount", 0)
                .put("warningCount", warningCount);
        UUID recordKey = UUID.randomUUID();
        String summary = "저장된 전체 활성 자료의 구조와 등록 규칙·예시 집합을 검사했습니다. 의미·품질 승인은 아닙니다.";
        long record =
                db.queryForObject(
                        "INSERT INTO"
                            + " review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,model_id,effort,evidence,self_review_yn)"
                            + " VALUES (?,'STRUCTURE',?,?::jsonb,'PASS',?,NULL,NULL,?,?) RETURNING"
                            + " id",
                        Long.class,
                        snapshot,
                        recordKey,
                        evidence.toString(),
                        actor.accountId(),
                        summary,
                        self);
        int changed =
                db.update(
                        "UPDATE story_version SET"
                            + " status='REVIEW',current_snapshot_id=?,edit_rev=?,updated_by=?,updated_at=clock_timestamp()"
                            + " WHERE id=? AND edit_rev=? AND status='DRAFT' AND"
                            + " current_snapshot_id IS NULL AND active_yn",
                        snapshot,
                        scope.rev + 1,
                        actor.accountId(),
                        scope.versionId,
                        scope.rev);
        if (changed != 1) throw AuthException.conflict("EDIT_CONFLICT");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("revisionScope", "CONTENT");
        detail.put("snapshotId", Long.toString(snapshot));
        detail.put("recordId", Long.toString(record));
        detail.put("sourceRev", Long.toString(scope.rev));
        detail.put("beforeStatus", "DRAFT");
        detail.put("afterStatus", "REVIEW");
        insertAudit(
                scope.storyId,
                scope.versionId,
                actor,
                "REVIEW_REQUESTED",
                scope.rev,
                scope.rev + 1,
                detail);
        requireReviewWrite(
                scope, actor, key, frozen, snapshot, record, recordKey, evidence, summary, self,
                detail);
        return snapshot;
    }

    /**
     * 모든 쓰기 뒤 실제 사본·구조 기록·현재 버전·원본·감사를 검증한 입력과 대조한다.
     *
     * @param scope 전환 전 잠금 문맥
     * @param actor 현재 편집 행위자
     * @param key 원래 브라우저 요청 UUID
     * @param frozen 전체 검증을 통과한 원래 사본
     * @param snapshot 새 실제 사본 ID
     * @param record 새 실제 구조 기록 ID
     * @param recordKey 서버에서 생성한 별도 구조 기록 UUID
     * @param evidence 서버가 구성한 구조 검사 근거
     * @param summary 원문 없는 고정 구조 검사 요약
     * @param self 서버에서 계산한 작성 참여 여부
     * @param detail 같은 거래에서 기록한 안전한 감사 상세
     * @throws AuthException 저장 후 식별·내용·전환 불일치이면 원인 없이 전체 거래를 롤백한다
     */
    private void requireReviewWrite(
            ReviewScope scope,
            AdminActor actor,
            UUID key,
            com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot frozen,
            long snapshot,
            long record,
            UUID recordKey,
            JsonNode evidence,
            String summary,
            boolean self,
            Map<String, Object> detail) {
        Map<String, Object> stored =
                db.queryForMap(
                        "SELECT version_id,edit_rev,format_no,payload::text,request_key,created_by"
                                + " FROM review_snapshot WHERE id=?",
                        snapshot);
        Map<String, Object> result =
                db.queryForMap(
                        "SELECT"
                            + " snapshot_id,kind,request_key,evidence_data::text,result,reviewer_id,model_id,effort,evidence,self_review_yn"
                            + " FROM review_record WHERE id=?",
                        record);
        Map<String, Object> versionIdentity =
                db.queryForMap(
                        "SELECT v.story_id,v.version_no,v.updated_by,s.code,s.active_yn FROM"
                                + " story_version v JOIN story s ON s.id=v.story_id WHERE v.id=?",
                        scope.versionId);
        VersionRow current =
                db.query(
                        "SELECT"
                            + " id,edit_rev,status,title,intro,setting,difficulty,est_min,est_max,limit_sec,policy_code,culprit_code,method_answer,time_answer,motive_answer,timeline_origin,reveal_text,current_snapshot_id,active_yn,updated_at"
                            + " FROM story_version WHERE id=?",
                        rs -> rs.next() ? mapVersion(rs) : null,
                        scope.versionId);
        boolean matches =
                java.util.Objects.equals(stored.get("version_id"), scope.versionId)
                        && java.util.Objects.equals(stored.get("edit_rev"), scope.rev)
                        && ((Number) stored.get("format_no")).intValue() == 1
                        && key.equals(stored.get("request_key"))
                        && java.util.Objects.equals(stored.get("created_by"), actor.accountId())
                        && reviewJsonEquals(
                                sourceJson((String) stored.get("payload")), frozen.payload())
                        && java.util.Objects.equals(result.get("snapshot_id"), snapshot)
                        && "STRUCTURE".equals(result.get("kind"))
                        && "PASS".equals(result.get("result"))
                        && recordKey.equals(result.get("request_key"))
                        && java.util.Objects.equals(result.get("reviewer_id"), actor.accountId())
                        && result.get("model_id") == null
                        && result.get("effort") == null
                        && summary.equals(result.get("evidence"))
                        && Boolean.valueOf(self).equals(result.get("self_review_yn"))
                        && reviewJsonEquals(
                                sourceJson((String) result.get("evidence_data")), evidence)
                        && current != null
                        && current.id == scope.versionId
                        && current.rev == scope.rev + 1
                        && "REVIEW".equals(current.status)
                        && java.util.Objects.equals(current.snapshot, snapshot)
                        && current.active
                        && scope.policyCode.equals(current.policy)
                        && java.util.Objects.equals(versionIdentity.get("story_id"), scope.storyId)
                        && java.util.Objects.equals(
                                versionIdentity.get("updated_by"), actor.accountId())
                        && Boolean.TRUE.equals(versionIdentity.get("active_yn"))
                        && frozen.payload()
                                .get("storyCode")
                                .textValue()
                                .equals(versionIdentity.get("code"))
                        && frozen.payload().get("versionNo").intValue()
                                == ((Number) versionIdentity.get("version_no")).intValue();
        if (!matches) throw AuthException.unavailable("STORY_UNAVAILABLE");
        JsonNode sections =
                json.valueToTree(
                        Map.of(
                                "basic",
                                subset(fields(current), BASIC),
                                "answer",
                                subset(fields(current), ANSWER),
                                "reveal",
                                subset(fields(current), REVEAL)));
        ReviewSource source =
                reviewSource(
                        new ReviewScope(
                                scope.storyId,
                                scope.versionId,
                                scope.rev,
                                scope.expected,
                                true,
                                "REVIEW",
                                snapshot,
                                current.policy,
                                sections));
        try {
            var finalFrozen =
                    com.reasoning.common.story.model.StoryFrozenSnapshotProducer.freeze(
                            (String) versionIdentity.get("code"),
                            ((Number) versionIdentity.get("version_no")).intValue(),
                            scope.rev,
                            current.policy,
                            source.sections,
                            source.resources);
            if (!source.unresolvedCheckers.isEmpty()
                    || !java.util.Arrays.equals(finalFrozen.payloadBytes(), frozen.payloadBytes()))
                throw AuthException.unavailable("STORY_UNAVAILABLE");
        } catch (IllegalArgumentException invalid) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
        List<Map<String, Object>> audits =
                db.queryForList(
                        "SELECT"
                            + " story_id,version_id,actor_id,action,before_rev,after_rev,detail::text"
                            + " FROM story_audit WHERE detail->>'requestId'=?",
                        detail.get("requestId").toString());
        if (audits.size() != 1) throw AuthException.unavailable("STORY_UNAVAILABLE");
        Map<String, Object> audit = audits.getFirst();
        if (!java.util.Objects.equals(audit.get("story_id"), scope.storyId)
                || !java.util.Objects.equals(audit.get("version_id"), scope.versionId)
                || !java.util.Objects.equals(audit.get("actor_id"), actor.accountId())
                || !"REVIEW_REQUESTED".equals(audit.get("action"))
                || !java.util.Objects.equals(audit.get("before_rev"), scope.rev)
                || !java.util.Objects.equals(audit.get("after_rev"), scope.rev + 1)
                || !reviewJsonEquals(
                        sourceJson((String) audit.get("detail")), json.valueToTree(detail)))
            throw AuthException.unavailable("STORY_UNAVAILABLE");
    }

    /**
     * 수동 검수·반환의 현재 인증과 행동 권한을 부모 잠금 아래 확정한다. 소유자도 REVIEW를 상속하지 않는다.
     *
     * @param sid null이 아닌 현재 저장 세션 ID
     * @param actor null이 아닌 현재 인증 행위자
     * @param code null이 아닌 사건 코드
     * @param number 양의 버전 번호
     * @param expectedRev null이 아닌 정규 수정번호이며 재생 이전에는 현재 번호와 비교하지 않는다
     * @param review true이면 전역·사건 REVIEW, false이면 owner/EDIT
     * @param work null이 아닌 같은 거래의 짧은 저장 작업
     * @return 현재 인가에서 확정한 결과
     * @throws AuthException 현재 인증·비공개 대상·행동 권한·잠금 실패 시
     */
    <T> T withReviewAction(
            String sid,
            AdminActor actor,
            String code,
            int number,
            String expectedRev,
            boolean review,
            java.util.function.Function<ReviewScope, T> work) {
        path(code, number);
        long expected = revision(expectedRev);
        precheck(sid, actor);
        return transact(
                () -> {
                    Account account = authorizeLocked(sid, actor);
                    StoryRow story = story(code);
                    permit(story, account, actor, false);
                    VersionRow v = version(story.id, number);
                    if (!v.active && story.owner != actor.accountId()) throw missing();
                    if (review) {
                        if (!account.review
                                || activeStoryPermissionCount(story.id, actor.accountId(), "REVIEW")
                                        != 1) throw AuthException.forbidden("FORBIDDEN");
                    } else permit(story, account, actor, true);
                    return work.apply(
                            new ReviewScope(
                                    story.id,
                                    v.id,
                                    v.rev,
                                    expected,
                                    story.active && v.active,
                                    v.status,
                                    v.snapshot,
                                    v.policy,
                                    json.valueToTree(
                                            Map.of(
                                                    "basic",
                                                    subset(fields(v), BASIC),
                                                    "answer",
                                                    subset(fields(v), ANSWER),
                                                    "reveal",
                                                    subset(fields(v), REVEAL)))));
                },
                null);
    }

    /**
     * BATCH 전용 소유 거래에서 현재 인증→runtime→활성 부모→자식 순서와 확정 직전 자격을 보장한다.
     *
     * @param sid null이 아닌 저장 세션 ID
     * @param actor null이 아닌 서버 행위자
     * @param code null이 아닌 사건 코드
     * @param number 양의 버전 번호
     * @param runtimeLock null이 아닌 인증 뒤 부모 잠금 전에 수행할 실제 runtime 잠금
     * @param work null이 아닌 현재 전역·사건 REVIEW를 확인한 뒤 수행할 짧은 저장 작업
     * @return 커밋된 작업 결과
     * @throws AuthException 현재 인증·REVIEW·활성 부모 또는 거래 경계가 잘못된 경우
     */
    <R, T> T withBatchAction(
            String sid,
            AdminActor actor,
            String code,
            int number,
            java.util.function.Supplier<R> runtimeLock,
            java.util.function.BiFunction<ReviewScope, R, T> work) {
        return withExecutionAction(
                sid,
                actor,
                code,
                number,
                runtimeLock,
                (scope, runtime) -> new GradeEvidenceWork<>(work.apply(scope, runtime), null),
                false);
    }

    /** 실제 GRADE 기록 한 건만 추가하는 고정 정책이며 재생은 추가 식별자를 반환하지 않는다. */
    <R, T> T withGradeEvidenceAction(
            String sid,
            AdminActor actor,
            String code,
            int number,
            java.util.function.Supplier<R> runtimeLock,
            java.util.function.BiFunction<ReviewScope, R, GradeEvidenceWork<T>> work) {
        return withExecutionAction(sid, actor, code, number, runtimeLock, work, true);
    }

    /** 현재 인증·runtime·부모·자식·최종 인가를 공유하되 기록 추가 정책은 내부에서만 선택한다. */
    private <R, T> T withExecutionAction(
            String sid,
            AdminActor actor,
            String code,
            int number,
            java.util.function.Supplier<R> runtimeLock,
            java.util.function.BiFunction<ReviewScope, R, GradeEvidenceWork<T>> work,
            boolean gradeEvidence) {
        path(code, number);
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw AuthException.unavailable("STORY_UNAVAILABLE");
        precheck(sid, actor);
        return batchTx.execute(
                status -> {
                    Boolean valid =
                            db.execute(
                                    (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                                            connection ->
                                                    !connection.getAutoCommit()
                                                            && !connection.isReadOnly()
                                                            && connection.getTransactionIsolation()
                                                                    == java.sql.Connection
                                                                            .TRANSACTION_READ_COMMITTED);
                    if (!Boolean.TRUE.equals(valid))
                        throw AuthException.unavailable("STORY_UNAVAILABLE");
                    db.execute("SET LOCAL lock_timeout = '5s'");
                    lockAccounts(List.of(actor.accountId()));
                    Account account = authorizeLocked(sid, actor);
                    R runtime = runtimeLock.get();
                    StoryRow story = story(code);
                    permit(story, account, actor, false);
                    VersionRow v = version(story.id, number);
                    if (!story.active || !v.active) throw missing();
                    if (!account.review
                            || activeStoryPermissionCount(story.id, actor.accountId(), "REVIEW")
                                    != 1) throw AuthException.forbidden("FORBIDDEN");
                    authorizeLocked(sid, actor);
                    ReviewScope scope =
                            new ReviewScope(
                                    story.id,
                                    v.id,
                                    v.rev,
                                    v.rev,
                                    true,
                                    v.status,
                                    v.snapshot,
                                    v.policy,
                                    json.createObjectNode());
                    JsonNode before = reviewStoredState(scope);
                    GradeEvidenceWork<T> result = work.apply(scope, runtime);
                    Long record = result.newRecordId();
                    if (gradeEvidence) {
                        if (!(result.response()
                                        instanceof
                                        StoryGradeEvidenceService.EvidenceResult evidence)
                                || !"EVIDENCE_CREATE".equals(evidence.action())
                                || (record == null
                                        ? !evidence.replayed() || evidence.changed()
                                        : evidence.replayed()
                                                || !evidence.changed()
                                                || !Long.toString(record)
                                                        .equals(evidence.original().recordId())))
                            throw AuthException.unavailable("STORY_UNAVAILABLE");
                    }
                    if (record != null) {
                        if (!gradeEvidence) throw AuthException.unavailable("STORY_UNAVAILABLE");
                        requireGradeAppend(scope, actor, record, result.requestKey());
                        for (JsonNode old : before.path("records"))
                            if (old.path("id").longValue() == record)
                                throw AuthException.unavailable("STORY_UNAVAILABLE");
                    }
                    if (!reviewJsonEquals(before, reviewStoredState(scope, record, null)))
                        throw AuthException.unavailable("STORY_UNAVAILABLE");
                    Account current = authorizeLocked(sid, actor);
                    if (!current.review
                            || activeStoryPermissionCount(story.id, actor.accountId(), "REVIEW")
                                    != 1) throw AuthException.forbidden("FORBIDDEN");
                    return result.response();
                });
    }

    /** 제외할 한 행은 실제 현재 사본·생성자·명령 영수증·봉인된 GRADE 집합과 독립 대조한다. */
    private void requireGradeAppend(ReviewScope scope, AdminActor actor, long id, UUID requestKey) {
        if (requestKey == null) throw AuthException.unavailable("STORY_UNAVAILABLE");
        Boolean valid =
                db.queryForObject(
                        """
                        SELECT r.kind='GRADE' AND r.result='PASS' AND r.reviewer_id=?
                            AND r.snapshot_id=? AND r.model_id IS NULL AND r.effort IS NULL
                            AND r.evidence='Verified GRADE execution evidence'
                            AND r.self_review_yn=? AND e.kind='GRADE' AND e.created_by=?
                            AND e.snapshot_id=r.snapshot_id AND e.available_yn
                            AND e.created_at=r.created_at AND a.admin_id=r.reviewer_id
                            AND a.action='EVIDENCE_CREATE' AND a.scope_key=?
                            AND a.result_data->'original'->>'recordId'=r.id::text
                            AND a.result_data->'original'->>'setKey'=e.set_key::text
                            AND r.evidence_data->>'formatNo'='3'
                            AND r.evidence_data->'request'->>'expectedRev'=?
                            AND r.evidence_data->'details'->>'executionSetRef'=e.set_key::text
                            AND r.evidence_data->'details'->>'executionSetHash'=e.evidence_hash
                            AND r.evidence_data->'details'->>'reviewerRef'=?
                            AND r.request_key=?
                            AND EXISTS(SELECT 1 FROM evidence_item i WHERE i.set_id=e.id)
                        FROM review_record r JOIN evidence_set e ON e.id=r.evidence_set_id
                        JOIN test_action a ON a.request_key=r.request_key WHERE r.id=?
                        """,
                        Boolean.class,
                        actor.accountId(),
                        scope.snapshotId(),
                        reviewSelf(scope, actor),
                        actor.accountId(),
                        "version:" + scope.versionId(),
                        Long.toString(scope.rev()),
                        actor.accountKey().toString(),
                        requestKey,
                        id);
        if (!Boolean.TRUE.equals(valid)) throw AuthException.unavailable("STORY_UNAVAILABLE");
        JsonNode stored =
                sourceJson(
                        db.queryForObject(
                                "SELECT to_jsonb(r)::text FROM review_record r WHERE id=?",
                                String.class,
                                id));
        JsonNode set =
                sourceJson(
                        db.queryForObject(
                                "SELECT to_jsonb(e)::text FROM evidence_set e WHERE id=?",
                                String.class,
                                stored.path("evidence_set_id").longValue()));
        var runtime =
                db.queryForMap(
                        "SELECT code,config_hash FROM grade_runtime WHERE id=?",
                        set.path("runtime_id").longValue());
        JsonNode links;
        try {
            links =
                    StoryGradeEvidenceService.normalizeResolves(
                            stored.path("evidence_data").path("details").path("resolves"));
        } catch (AuthException invalid) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
        var wrapper = json.createObjectNode().put("formatNo", 3);
        wrapper.putObject("request").put("expectedRev", Long.toString(scope.rev()));
        var details =
                wrapper.putObject("details")
                        .put("formatNo", 3)
                        .put("snapshotId", Long.toString(scope.snapshotId()))
                        .put(
                                "payloadHash",
                                set.path("summary_data").path("payload_hash").textValue())
                        .put("rubricHash", set.path("summary_data").path("rubric_hash").textValue())
                        .put("runtimeConfigId", (String) runtime.get("code"))
                        .put("configHash", (String) runtime.get("config_hash"))
                        .put("executionSetRef", set.path("set_key").textValue())
                        .put("executionSetHash", set.path("evidence_hash").textValue())
                        .put(
                                "checkedAt",
                                java.time.OffsetDateTime.parse(set.path("created_at").textValue())
                                        .toInstant()
                                        .toString())
                        .put("reviewerRef", actor.accountKey().toString())
                        .put("criticalOpenCount", 0);
        details.set("resolves", links);
        var expected =
                json.createObjectNode()
                        .put("id", id)
                        .put("snapshot_id", scope.snapshotId())
                        .put("kind", "GRADE")
                        .put("request_key", requestKey.toString())
                        .put("result", "PASS")
                        .put("reviewer_id", actor.accountId())
                        .putNull("model_id")
                        .putNull("effort")
                        .put("evidence", "Verified GRADE execution evidence")
                        .put("self_review_yn", reviewSelf(scope, actor))
                        .put("evidence_set_id", set.path("id").longValue());
        expected.set("evidence_data", wrapper);
        expected.set("created_at", set.get("created_at"));
        if (!reviewJsonEquals(expected, stored))
            throw AuthException.unavailable("STORY_UNAVAILABLE");
    }

    record GradeEvidenceWork<T>(T response, Long newRecordId, UUID requestKey) {
        GradeEvidenceWork(T response, Long newRecordId) {
            this(response, newRecordId, null);
        }
    }

    /** 같은 부모 소속만 확인하며 재생에 과거 상태나 수정번호를 다시 요구하지 않는다. */
    private Map<String, Object> reviewSnapshot(ReviewScope scope, long selected) {
        var rows =
                db.queryForList(
                        "SELECT id,edit_rev FROM review_snapshot WHERE version_id=? AND id=?",
                        scope.versionId,
                        selected);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    /**
     * 실행 이력 전용 소유 거래에서 현재 인증 루트와 활성 부모의 REVIEW 쌍을 검사한다.
     *
     * @param sid null이 아닌 현재 저장 세션 ID
     * @param actor null이 아닌 현재 서버 행위자
     * @param code null이 아닌 활성 사건 코드
     * @param number 활성 버전 번호
     * @param work null이 아닌 같은 거래의 메타데이터 조회·필수 감사 작업
     * @return 커밋된 조회 결과
     * @throws AuthException 인증·비노출 부모·REVIEW·잠금·저장·보존 실패 시
     */
    <T> T withExecutionIssueRead(
            String sid,
            AdminActor actor,
            String code,
            int number,
            java.util.function.Function<ReviewScope, T> work) {
        path(code, number);
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw AuthException.unavailable("STORY_UNAVAILABLE");
        precheck(sid, actor);
        try {
            return batchTx.execute(
                    status -> {
                        Boolean valid =
                                db.execute(
                                        (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                                                connection ->
                                                        !connection.getAutoCommit()
                                                                && !connection.isReadOnly()
                                                                && connection
                                                                                .getTransactionIsolation()
                                                                        == java.sql.Connection
                                                                                .TRANSACTION_READ_COMMITTED);
                        if (!Boolean.TRUE.equals(valid))
                            throw AuthException.unavailable("STORY_UNAVAILABLE");
                        db.execute("SET LOCAL lock_timeout = '5s'");
                        lockAccounts(List.of(actor.accountId()));
                        Account account = authorizeLocked(sid, actor);
                        StoryRow story = story(code);
                        permit(story, account, actor, false);
                        if (!story.active) throw missing();
                        ReviewScope scope =
                                db.query(
                                        "SELECT id,edit_rev,status,current_snapshot_id,active_yn"
                                                + " FROM story_version WHERE story_id=? AND"
                                                + " version_no=? FOR UPDATE",
                                        rs ->
                                                rs.next()
                                                        ? new ReviewScope(
                                                                story.id,
                                                                rs.getLong(1),
                                                                rs.getLong(2),
                                                                rs.getLong(2),
                                                                rs.getBoolean(5),
                                                                rs.getString(3),
                                                                (Long) rs.getObject(4),
                                                                null,
                                                                json.createObjectNode())
                                                        : null,
                                        story.id,
                                        number);
                        if (scope == null || !scope.active) throw missing();
                        if (!account.review
                                || activeStoryPermissionCount(story.id, actor.accountId(), "REVIEW")
                                        != 1) throw AuthException.forbidden("FORBIDDEN");

                        T result = work.apply(scope);
                        Account current = authorizeLocked(sid, actor);
                        if (!current.review
                                || activeStoryPermissionCount(story.id, actor.accountId(), "REVIEW")
                                        != 1) throw AuthException.forbidden("FORBIDDEN");
                        return result;
                    });
        } catch (DataAccessException failure) {
            String state = sqlState(failure);
            throw AuthException.unavailable(
                    "55P03".equals(state) || "40P01".equals(state)
                            ? "STORY_BUSY"
                            : "STORY_UNAVAILABLE");
        }
    }

    /**
     * 사본의 실제 버전 소속과 원래 revision만 확인한다.
     *
     * @param scope 현재 인가한 부모
     * @param selected 양의 사본 ID
     * @return 저장 sourceRev
     * @throws AuthException 다른 부모·없는 사본이면404
     */
    long executionIssueSnapshot(ReviewScope scope, long selected) {
        var rows =
                db.queryForList(
                        "SELECT edit_rev FROM review_snapshot WHERE version_id=? AND id=?",
                        Long.class,
                        scope.versionId,
                        selected);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    /**
     * 실제 BATCH·runtime·관리자 조인과 닫힌 해소 필드만 읽는다.
     *
     * @param selected 부모 소속 검증이 끝난 사본 ID
     * @param state null 또는 OPEN/RESOLVED
     * @param cursor null 또는 배타 내부 ID
     * @param size 반환 상한1~100
     * @return 내부 커서와 정확한 공개 DTO의 size+1 목록
     * @throws AuthException 저장 해소 필드·실제 조인이 잘못되면503
     */
    List<ExecutionIssueRow> executionIssueRows(long selected, String state, Long cursor, int size) {
        List<Object> args = new ArrayList<>();
        args.add(selected);
        if (state != null) args.add(state);
        if (cursor != null) args.add(cursor);
        args.add(size + 1);
        return db.query(
                "SELECT i.id,i.issue_key,i.snapshot_id,r.code,b.batch_key,i.kind,i.severity,"
                    + "i.state,i.created_at,i.resolved_at,a.account_key,t.batch_key,"
                    + "jsonb_typeof(i.resolution_data),jsonb_typeof(i.resolution_data->'reasonCode'),"
                    + "i.resolution_data->>'reasonCode',"
                    + "jsonb_typeof(i.resolution_data->'verificationRef'),i.resolution_data->>'verificationRef'"
                    + " FROM execution_issue i LEFT JOIN grade_runtime r ON r.id=i.runtime_id LEFT"
                    + " JOIN grade_batch b ON b.id=i.batch_id AND b.snapshot_id=i.snapshot_id AND"
                    + " b.runtime_id=i.runtime_id LEFT JOIN grade_batch t ON"
                    + " t.id=i.resolved_batch_id AND t.snapshot_id=i.snapshot_id LEFT JOIN"
                    + " admin_account a ON a.id=i.resolved_by WHERE i.snapshot_id=?"
                        + (state == null ? "" : " AND i.state=?")
                        + (cursor == null ? "" : " AND i.id<?")
                        + " ORDER BY i.id DESC LIMIT ?",
                (rs, row) -> {
                    String runtime = rs.getString(4);
                    UUID source = (UUID) rs.getObject(5);
                    String storedState = rs.getString(8);
                    java.sql.Timestamp resolved = rs.getTimestamp(10);
                    UUID resolver = (UUID) rs.getObject(11);
                    UUID target = (UUID) rs.getObject(12);
                    StoryReviewService.IssueResolution resolution = null;
                    if (runtime == null || source == null)
                        throw AuthException.unavailable("STORY_UNAVAILABLE");
                    if ("RESOLVED".equals(storedState)) {
                        String reason = rs.getString(15);
                        String ref = rs.getString(17);
                        if (resolved == null
                                || resolver == null
                                || target == null
                                || !"object".equals(rs.getString(13))
                                || !"string".equals(rs.getString(14))
                                || !Set.of(
                                                "GRADING_FIX_VERIFIED",
                                                "INFRA_RECOVERED",
                                                "OBSERVATION_COMPLETED")
                                        .contains(reason == null ? "" : reason)
                                || !"string".equals(rs.getString(16))
                                || ref == null
                                || !ref.matches("[A-Za-z0-9_-]{8,64}"))
                            throw AuthException.unavailable("STORY_UNAVAILABLE");
                        resolution = new StoryReviewService.IssueResolution(reason, ref);
                    } else if (!"OPEN".equals(storedState)
                            || resolved != null
                            || resolver != null
                            || target != null
                            || rs.getString(13) != null)
                        throw AuthException.unavailable("STORY_UNAVAILABLE");

                    return new ExecutionIssueRow(
                            rs.getLong(1),
                            new StoryReviewService.ExecutionIssue(
                                    (UUID) rs.getObject(2),
                                    Long.toString(rs.getLong(3)),
                                    runtime,
                                    source,
                                    null,
                                    rs.getString(6),
                                    rs.getString(7),
                                    storedState,
                                    rs.getTimestamp(9).toInstant(),
                                    resolved == null ? null : resolved.toInstant(),
                                    resolver,
                                    target,
                                    null,
                                    resolution));
                },
                args.toArray());
    }

    /** 내부 정렬 ID는 항목 DTO에 넣지 않는다. */
    record ExecutionIssueRow(long id, StoryReviewService.ExecutionIssue item) {}

    /**
     * 새 CONTENT_READ 한 행만 허용하고 실제 저장 감사와 모든 보존 행을 대조한다.
     *
     * @param scope 현재 인가된 부모
     * @param actor 현재 관리자
     * @param sourceRev 선택 사본의 원래 수정번호
     * @param selected 선택 사본 ID
     * @param requestId 서버 상관 UUID
     * @throws AuthException 필수 감사 실패·성공 트리거 변조·추가·삭제이면503과 전체 롤백
     */
    void recordExecutionIssueRead(
            ReviewScope scope, AdminActor actor, long sourceRev, long selected, UUID requestId) {
        var before = executionIssueStoredState(scope, selected, null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("revisionScope", "CONTENT");
        detail.put("requestId", requestId);
        detail.put("sourceRev", Long.toString(sourceRev));
        detail.put("snapshotId", Long.toString(selected));
        insertAudit(
                scope.storyId,
                scope.versionId,
                actor,
                "CONTENT_READ",
                scope.rev,
                scope.rev,
                detail);
        requireReviewAudit(scope, actor, "CONTENT_READ", scope.rev, detail);
        Long auditId =
                db.queryForObject(
                        "SELECT id FROM story_audit WHERE version_id=?"
                                + " AND detail->>'requestId'=?",
                        Long.class,
                        scope.versionId,
                        requestId.toString());
        if (!reviewJsonEquals(before, executionIssueStoredState(scope, selected, auditId)))
            throw AuthException.unavailable("STORY_UNAVAILABLE");
    }

    /**
     * 보존 전용 SQL 해시와 실제 전체 행 ID로 실행·원고·감사의 성공 변조도 탐지한다.
     *
     * @param scope 현재 잠긴 부모
     * @param selected 부모 소속을 확인한 조회 사본 ID; runtime 보존 범위의 실제 집합 관계
     * @param auditId 비교에서 제외할 이번 실제 감사 ID 또는 null
     * @return 원문 없는 보존 지문
     */
    private JsonNode executionIssueStoredState(ReviewScope scope, long selected, Long auditId) {
        var result = json.createObjectNode();
        for (String table :
                List.of(
                        "story",
                        "story_version",
                        "story_person",
                        "story_role",
                        "story_pair",
                        "story_clue",
                        "clue_role",
                        "story_hint",
                        "story_event",
                        "story_fact",
                        "story_rubric",
                        "rubric_clue",
                        "grade_sample",
                        "review_snapshot",
                        "review_record",
                        "grade_runtime",
                        "grade_batch",
                        "grade_job",
                        "grade_attempt",
                        "grade_event",
                        "execution_issue",
                        "test_action",
                        "test_audit",
                        "story_audit")) {
            String predicate =
                    switch (table) {
                        case "story", "story_version" -> "t.id=?";
                        case "grade_runtime" ->
                                "t.id IN (SELECT b.runtime_id FROM grade_batch b WHERE"
                                        + " b.snapshot_id=?)";
                        case "grade_batch", "grade_job", "execution_issue", "review_record" ->
                                "t.snapshot_id IN (SELECT id FROM review_snapshot WHERE"
                                        + " version_id=?)";
                        case "grade_attempt", "grade_event" ->
                                "t.job_id IN (SELECT j.id FROM grade_job j JOIN review_snapshot s"
                                        + " ON s.id=j.snapshot_id WHERE s.version_id=?)";
                        case "test_action" -> "t.scope_key='version:' || ?::text";
                        case "test_audit" ->
                                "t.scope_key='version:' || ?::text OR t.scope_key IN (SELECT"
                                        + " 'batch:' || b.batch_key::text FROM grade_batch b JOIN"
                                        + " review_snapshot s ON s.id=b.snapshot_id WHERE"
                                        + " s.version_id=?)";
                        case "story_audit" -> "t.story_id=? AND (?::bigint IS NULL OR t.id<>?)";
                        default -> "t.version_id=?";
                    };
            Object[] args =
                    switch (table) {
                        case "story" -> new Object[] {scope.storyId};
                        case "grade_runtime" -> new Object[] {selected};
                        case "test_audit" -> new Object[] {scope.versionId, scope.versionId};
                        case "story_audit" -> new Object[] {scope.storyId, auditId, auditId};
                        default -> new Object[] {scope.versionId};
                    };
            String key =
                    switch (table) {
                        case "grade_attempt" -> "jsonb_build_array(t.job_id,t.attempt_no)";
                        case "story_pair" -> "jsonb_build_array(t.role_a,t.role_b)";
                        case "clue_role" -> "jsonb_build_array(t.clue_code,t.role_code)";
                        case "rubric_clue" -> "jsonb_build_array(t.rubric_code,t.clue_code)";
                        case "story_person",
                                "story_role",
                                "story_clue",
                                "story_hint",
                                "story_event",
                                "story_fact",
                                "story_rubric",
                                "grade_sample" ->
                                "to_jsonb(t.code)";
                        default -> "to_jsonb(t.id)";
                    };
            result.set(
                    table,
                    sourceJson(
                            db.queryForObject(
                                    "SELECT coalesce(jsonb_agg(jsonb_build_object('id',"
                                            + key
                                            + ",'hash',encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'))"
                                            + " ORDER BY "
                                            + key
                                            + "),"
                                            + "'[]'::jsonb)::text FROM "
                                            + table
                                            + " t WHERE "
                                            + predicate,
                                    String.class,
                                    args)));
        }
        return result;
    }

    /** 원래 모든 정규 입력과 행위자를 비교하여 신규 append 또는 무쓰기 replay만 허용한다. */
    StoryReviewService.RecordResult createReviewRecord(
            ReviewScope scope,
            AdminActor actor,
            long selected,
            UUID key,
            com.reasoning.common.story.model.StoryReviewEvidence.Input input,
            UUID requestId) {
        reviewSnapshot(scope, selected);
        var wrapper = json.createObjectNode().put("formatNo", 1);
        wrapper.putObject("request").put("expectedRev", Long.toString(scope.expected));
        wrapper.set("details", input.evidenceData());
        var existing =
                db.queryForList(
                        "SELECT * FROM review_record WHERE snapshot_id=? AND request_key=?",
                        selected,
                        key);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.getFirst();
            if (!java.util.Objects.equals(row.get("reviewer_id"), actor.accountId())
                    || !input.kind().equals(row.get("kind"))
                    || !input.result().equals(row.get("result"))
                    || !java.util.Objects.equals(input.modelId(), row.get("model_id"))
                    || !java.util.Objects.equals(input.effort(), row.get("effort"))
                    || !input.evidence().equals(row.get("evidence"))
                    || !reviewJsonEquals(sourceJson(row.get("evidence_data").toString()), wrapper))
                throw AuthException.conflict("REQUEST_KEY_CONFLICT");
            return new StoryReviewService.RecordResult(
                    row.get("id").toString(),
                    Long.toString(selected),
                    java.util.Objects.equals(scope.snapshotId, selected),
                    true,
                    requestId);
        }
        requireCurrentReview(scope, selected, false);
        com.reasoning.common.story.model.StoryReviewEvidence.requireNew(input);
        // 전체 wrapper의 실제 JSONB UTF-8 표현을 검사한다. DB CHECK 실패로 503을 만들지 않는다.
        int bytes =
                db.queryForObject(
                        "SELECT octet_length((?::jsonb)::text)", Integer.class, wrapper.toString());
        if (bytes > 131072) throw AuthException.unprocessable("INVALID_INPUT");
        var resolves =
                com.reasoning.common.story.model.StoryReviewEvidence.resolutions(
                        input.evidenceData());
        for (long id : resolves) {
            int matches =
                    db.queryForObject(
                            "SELECT count(*) FROM review_record WHERE id=? AND snapshot_id=? AND"
                                    + " kind=? AND result IN ('FAIL','INCOMPLETE')",
                            Integer.class,
                            id,
                            selected,
                            input.kind());
            if (matches != 1) throw AuthException.unprocessable("REVIEW_NOT_READY");
        }
        var before = reviewStoredState(scope);
        boolean self = reviewSelf(scope, actor);
        JsonNode now = sourceJson(db.queryForObject("SELECT to_jsonb(now())::text", String.class));
        long record =
                db.queryForObject(
                        "INSERT INTO"
                            + " review_record(snapshot_id,kind,request_key,evidence_data,result,reviewer_id,model_id,effort,evidence,self_review_yn)"
                            + " VALUES (?,?,?,?::jsonb,?,?,?,?,?,?) RETURNING id",
                        Long.class,
                        selected,
                        input.kind(),
                        key,
                        wrapper.toString(),
                        input.result(),
                        actor.accountId(),
                        input.modelId(),
                        input.effort(),
                        input.evidence(),
                        self);
        if (resolves.stream().anyMatch(id -> id >= record))
            throw AuthException.unprocessable("REVIEW_NOT_READY");
        Map<String, Object> detail = reviewDetail(scope, selected, requestId);
        detail.put("recordId", Long.toString(record));
        insertAudit(
                scope.storyId,
                scope.versionId,
                actor,
                "REVIEW_RECORDED",
                scope.rev,
                scope.rev,
                detail);
        var after = reviewStoredState(scope, record, requestId);
        var expected =
                json.createObjectNode()
                        .put("id", record)
                        .put("snapshot_id", selected)
                        .put("kind", input.kind())
                        .put("request_key", key.toString())
                        .put("result", input.result())
                        .put("reviewer_id", actor.accountId())
                        .put("model_id", input.modelId())
                        .put("effort", input.effort())
                        .put("evidence", input.evidence())
                        .put("self_review_yn", self)
                        .putNull("evidence_set_id");
        expected.set("evidence_data", wrapper);
        expected.set("created_at", now);
        JsonNode stored =
                sourceJson(
                        db.queryForObject(
                                "SELECT to_jsonb(r)::text FROM review_record r WHERE id=?",
                                String.class,
                                record));
        requireReviewAudit(scope, actor, "REVIEW_RECORDED", scope.rev, detail);
        if (!reviewJsonEquals(expected, stored) || !reviewJsonEquals(before, after))
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        return new StoryReviewService.RecordResult(
                Long.toString(record), Long.toString(selected), true, false, requestId);
    }

    /** 신규 결과와 반환은 활성 부모·허용 상태·현재 사본·최신 수정번호를 요구한다. */
    private void requireCurrentReview(ReviewScope scope, long selected, boolean returning) {
        if (!scope.active
                || !("REVIEW".equals(scope.status) || returning && "READY".equals(scope.status)))
            throw AuthException.conflict("STATE_CONFLICT");
        if (scope.rev != scope.expected) throw AuthException.conflict("EDIT_CONFLICT");
        if (!java.util.Objects.equals(scope.snapshotId, selected))
            throw AuthException.conflict("SNAPSHOT_CONFLICT");
    }

    /** 당시 작성·원고 변경 감사로 참여를 계산하며 과거 작성자의 현재 자격은 조회하지 않는다. */
    boolean reviewSelf(ReviewScope scope, AdminActor actor) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM story_audit WHERE story_id=? AND version_id=?"
                            + " AND actor_id=? AND action IN"
                            + " ('STORY_CREATED','SECTION_UPDATED','ITEM_CREATED','ITEM_UPDATED','ITEM_DEACTIVATED','ITEM_REACTIVATED'))",
                        Boolean.class,
                        scope.storyId,
                        scope.versionId,
                        actor.accountId()));
    }

    /** 근거 목록의 부모 소속·원래 revision만 읽으며 16MiB payload를 불필요하게 가져오지 않는다. */
    SnapshotRow reviewRecordSnapshot(ReadScope scope, long selected) {
        SnapshotRow row =
                db.query(
                        "SELECT s.id,s.edit_rev,s.format_no,s.created_at,a.account_key FROM"
                            + " review_snapshot s LEFT JOIN admin_account a ON a.id=s.created_by"
                            + " WHERE s.version_id=? AND s.id=?",
                        rs -> rs.next() ? snapshotRow(rs, scope, false) : null,
                        scope.versionId,
                        selected);
        if (row == null) throw missing();
        return row;
    }

    /** 원문 근거를 저장 wrapper에서 분리하며 비활성 검수자의 역사적 UUID도 보존한다. */
    List<StoryReviewService.RecordItem> reviewRecordRows(long selected, int size, Long cursor) {
        return db.query(
                "SELECT"
                    + " r.id,r.kind,r.result,a.account_key,r.model_id,r.effort,r.evidence,r.evidence_data::text,r.self_review_yn,r.created_at"
                    + " FROM review_record r LEFT JOIN admin_account a ON a.id=r.reviewer_id WHERE"
                    + " r.snapshot_id=?"
                        + (cursor == null ? "" : " AND r.id<?")
                        + " ORDER BY r.id DESC LIMIT ?",
                (rs, row) -> {
                    UUID reviewer = (UUID) rs.getObject(4);
                    JsonNode saved = sourceJson(rs.getString(8));
                    if (reviewer == null || saved.get("details") == null)
                        throw AuthException.unavailable("STORY_UNAVAILABLE");
                    return new StoryReviewService.RecordItem(
                            Long.toString(rs.getLong(1)),
                            rs.getString(2),
                            rs.getString(3),
                            reviewer,
                            rs.getString(5),
                            rs.getString(6),
                            rs.getString(7),
                            saved.get("details"),
                            rs.getBoolean(9),
                            rs.getTimestamp(10).toInstant());
                },
                cursor == null
                        ? new Object[] {selected, size + 1}
                        : new Object[] {selected, cursor, size + 1});
    }

    /** 근거 원문이 서비스 밖으로 나가기 전에 필수 조회 감사의 실제 저장과 보존을 확인한다. */
    void recordReviewRecordsRead(
            ReadScope scope, AdminActor actor, long sourceRev, long selected, UUID requestId) {
        ReviewScope review =
                new ReviewScope(
                        scope.storyId,
                        scope.versionId,
                        scope.rev,
                        scope.rev,
                        scope.active,
                        scope.status,
                        scope.snapshotId,
                        null,
                        scope.sections);
        var before = reviewStoredState(review);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("revisionScope", "CONTENT");
        detail.put("requestId", requestId);
        detail.put("sourceRev", Long.toString(sourceRev));
        detail.put("snapshotId", Long.toString(selected));
        insertAudit(
                scope.storyId,
                scope.versionId,
                actor,
                "CONTENT_READ",
                scope.rev,
                scope.rev,
                detail);
        var after = reviewStoredState(review, null, requestId);
        requireReviewAudit(review, actor, "CONTENT_READ", scope.rev, detail);
        if (!reviewJsonEquals(before, after)) throw AuthException.unavailable("STORY_UNAVAILABLE");
    }

    /** 반환은 현재 포인터만 끊으며 내용·체커·과거 append-only 기록을 그대로 둔다. */
    StoryReviewService.ReviewResult returnReviewDraft(
            ReviewScope scope,
            AdminActor actor,
            long selected,
            String action,
            String reason,
            String ref,
            UUID requestId) {
        requireCurrentReview(scope, selected, true);
        Map<String, Object> snapshot = reviewSnapshot(scope, selected);
        if (scope.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
        var before = reviewStoredState(scope);
        Instant earliest =
                db.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        String updated =
                db.queryForObject(
                        "UPDATE story_version SET"
                            + " status='DRAFT',current_snapshot_id=NULL,edit_rev=edit_rev+1,updated_by=?,updated_at=clock_timestamp()"
                            + " WHERE id=? AND edit_rev=? AND current_snapshot_id=? AND status IN"
                            + " ('REVIEW','READY') AND active_yn RETURNING"
                            + " to_jsonb(updated_at)::text",
                        String.class,
                        actor.accountId(),
                        scope.versionId,
                        scope.rev,
                        selected);
        Instant latest =
                db.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        Instant storedTime =
                java.time.OffsetDateTime.parse(sourceJson(updated).textValue()).toInstant();
        if (storedTime.isBefore(earliest) || storedTime.isAfter(latest))
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        Map<String, Object> detail = reviewDetail(scope, selected, requestId);
        detail.put("beforeStatus", scope.status);
        detail.put("afterStatus", "DRAFT");
        detail.put("reasonCode", reason);
        detail.put("verificationRef", ref);
        String event = "WITHDRAW".equals(action) ? "REVIEW_WITHDRAWN" : "REVIEW_RETURNED";
        insertAudit(scope.storyId, scope.versionId, actor, event, scope.rev, scope.rev + 1, detail);
        var expectedVersion =
                (com.fasterxml.jackson.databind.node.ObjectNode) before.get("version");
        expectedVersion
                .put("status", "DRAFT")
                .putNull("current_snapshot_id")
                .put("edit_rev", scope.rev + 1)
                .put("updated_by", actor.accountId());
        expectedVersion.set("updated_at", sourceJson(updated));
        var after = reviewStoredState(scope, null, requestId);
        requireReviewAudit(scope, actor, event, scope.rev + 1, detail);
        if (!reviewJsonEquals(before, after)) throw AuthException.unavailable("STORY_UNAVAILABLE");
        return new StoryReviewService.ReviewResult(
                Long.toString(selected),
                snapshot.get("edit_rev").toString(),
                Long.toString(scope.rev + 1),
                "DRAFT",
                null,
                false,
                requestId);
    }

    /** 감사에는 저장 원문 대신 안전한 식별자만 넣는다. */
    private Map<String, Object> reviewDetail(ReviewScope scope, long selected, UUID requestId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("revisionScope", "CONTENT");
        detail.put("snapshotId", Long.toString(selected));
        detail.put(
                "sourceRev",
                db.queryForObject(
                                "SELECT edit_rev FROM review_snapshot WHERE id=?",
                                Long.class,
                                selected)
                        .toString());
        return detail;
    }

    /** 전체 현재·비활성 원고와 과거 사본·결과·감사를 대조해 트리거의 성공 변조도 롤백한다. */
    private com.fasterxml.jackson.databind.node.ObjectNode reviewStoredState(ReviewScope scope) {
        return reviewStoredState(scope, null, null);
    }

    /** 보존 행은 PostgreSQL 내장 SHA-256과 ID만 읽고 새 행은 별도 직접 대조한다. 성능은 미측정이다. */
    private com.fasterxml.jackson.databind.node.ObjectNode reviewStoredState(
            ReviewScope scope, Long record, UUID requestId) {
        var state = json.createObjectNode();
        state.set(
                "story",
                sourceJson(
                        db.queryForObject(
                                "SELECT to_jsonb(s)::text FROM story s WHERE id=?",
                                String.class,
                                scope.storyId)));
        state.set(
                "version",
                sourceJson(
                        db.queryForObject(
                                "SELECT to_jsonb(v)::text FROM story_version v WHERE id=?",
                                String.class,
                                scope.versionId)));
        for (String table :
                List.of(
                        "story_person",
                        "story_role",
                        "story_pair",
                        "story_clue",
                        "clue_role",
                        "story_hint",
                        "story_event",
                        "story_fact",
                        "story_rubric",
                        "rubric_clue",
                        "grade_sample",
                        "review_snapshot")) {
            String key =
                    switch (table) {
                        case "review_snapshot" -> "jsonb_build_array(t.id)";
                        case "story_pair" -> "jsonb_build_array(t.role_a,t.role_b)";
                        case "clue_role" -> "jsonb_build_array(t.clue_code,t.role_code)";
                        case "rubric_clue" -> "jsonb_build_array(t.rubric_code,t.clue_code)";
                        default -> "jsonb_build_array(t.code)";
                    };
            state.set(
                    table,
                    sourceJson(
                            db.queryForObject(
                                    "SELECT coalesce(jsonb_agg(jsonb_build_object('key',"
                                            + key
                                            + ",'hash',encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'))"
                                            + " ORDER BY ("
                                            + key
                                            + ")::text COLLATE \"C\"),'[]'::jsonb)::text FROM "
                                            + table
                                            + " t WHERE version_id=?",
                                    String.class,
                                    scope.versionId)));
        }
        state.set(
                "records",
                sourceJson(
                        db.queryForObject(
                                "SELECT"
                                    + " coalesce(jsonb_agg(jsonb_build_object('id',r.id,'hash',encode(sha256(convert_to(to_jsonb(r)::text,'UTF8')),'hex'))"
                                    + " ORDER BY r.id),'[]'::jsonb)::text FROM review_record r JOIN"
                                    + " review_snapshot s ON s.id=r.snapshot_id WHERE"
                                    + " s.version_id=? AND (?::bigint IS NULL OR r.id<>?)",
                                String.class,
                                scope.versionId,
                                record,
                                record)));
        for (String table : List.of("evidence_set", "evidence_item")) {
            String key =
                    "evidence_set".equals(table)
                            ? "jsonb_build_array(t.id)"
                            : "jsonb_build_array(t.set_id,t.batch_id)";
            String set = "evidence_set".equals(table) ? "t.id" : "t.set_id";
            state.set(
                    table,
                    sourceJson(
                            db.queryForObject(
                                    "SELECT coalesce(jsonb_agg(jsonb_build_object('key',"
                                            + key
                                            + ",'hash',encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex'))"
                                            + " ORDER BY ("
                                            + key
                                            + ")::text COLLATE \"C\"),'[]'::jsonb)::text FROM "
                                            + table
                                            + " t JOIN review_snapshot s ON s.id=t.snapshot_id"
                                            + " WHERE s.version_id=? AND "
                                            + set
                                            + " IS DISTINCT FROM (SELECT evidence_set_id FROM"
                                            + " review_record WHERE id=? AND kind='GRADE')",
                                    String.class,
                                    scope.versionId,
                                    record)));
        }
        state.set(
                "story_audit",
                sourceJson(
                        db.queryForObject(
                                "SELECT"
                                    + " coalesce(jsonb_agg(jsonb_build_object('id',a.id,'hash',encode(sha256(convert_to(to_jsonb(a)::text,'UTF8')),'hex'))"
                                    + " ORDER BY a.id),'[]'::jsonb)::text FROM story_audit a WHERE"
                                    + " version_id=? AND (?::text IS NULL OR detail->>'requestId'"
                                    + " IS DISTINCT FROM ?)",
                                String.class,
                                scope.versionId,
                                requestId == null ? null : requestId.toString(),
                                requestId == null ? null : requestId.toString())));
        return state;
    }

    /** 새 필수 감사 한 행을 정확히 검사하고 보존 비교에서 분리한다. */
    private void requireReviewAudit(
            ReviewScope scope,
            AdminActor actor,
            String event,
            long afterRev,
            Map<String, Object> detail) {
        List<String> rows =
                db.queryForList(
                        "SELECT to_jsonb(a)::text FROM story_audit a WHERE version_id=? AND"
                                + " detail->>'requestId'=?",
                        String.class,
                        scope.versionId,
                        detail.get("requestId").toString());
        if (rows.size() != 1) throw AuthException.unavailable("STORY_UNAVAILABLE");
        JsonNode found = sourceJson(rows.getFirst());
        if (found.path("story_id").asLong() != scope.storyId
                || found.path("version_id").asLong() != scope.versionId
                || found.path("actor_id").asLong() != actor.accountId()
                || !found.path("target_admin_id").isNull()
                || !reviewJsonEquals(
                        found.get("created_at"),
                        sourceJson(db.queryForObject("SELECT to_jsonb(now())::text", String.class)))
                || !event.equals(found.path("action").asText())
                || found.path("before_rev").asLong() != scope.rev
                || found.path("after_rev").asLong() != afterRev
                || !reviewJsonEquals(found.get("detail"), json.valueToTree(detail)))
            throw AuthException.unavailable("STORY_UNAVAILABLE");
    }

    /** Jackson 숫자 노드 하위 타입이 아닌 승인된 전체 정규 JSON 바이트로 비교한다. */
    private static boolean reviewJsonEquals(JsonNode actual, JsonNode expected) {
        return java.util.Arrays.equals(
                com.reasoning.common.grading.model.SnapshotJson.encode(actual),
                com.reasoning.common.grading.model.SnapshotJson.encode(expected));
    }

    record ReviewScope(
            long storyId,
            long versionId,
            long rev,
            long expected,
            boolean active,
            String status,
            Long snapshotId,
            String policyCode,
            JsonNode sections) {}

    record ReviewSource(JsonNode sections, JsonNode resources, List<String> unresolvedCheckers) {}

    record ReviewReplay(long snapshotId, long sourceRev, long createdBy) {}

    /** 현재 조회에 필요한 문맥이며 정책 생성·편집 권한·전체 자원 사본은 포함하지 않는다. */
    record ReadScope(
            long storyId,
            long versionId,
            long rev,
            boolean active,
            String status,
            Long snapshotId,
            JsonNode sections) {}

    /** 부모 인가 후 읽은 저장 메타데이터이며 목록의 payload는 null이다. */
    record SnapshotRow(
            long snapshotId,
            long sourceRev,
            int formatNo,
            Instant createdAt,
            UUID createdBy,
            boolean current,
            JsonNode payload) {}

    /**
     * 대상 노출과 선택적 편집 조건을 확인하고 잠금으로 읽은 현재 계정을 함께 전달한다.
     *
     * @param sid null이 아닌 현재 저장 세션 ID
     * @param actor null이 아닌 서버 인증 행위자
     * @param code 검증된 사건 코드이며 null이 아니다
     * @param number 양의 버전 번호
     * @param expected 편집 시 비교할 수정번호이며 조회이면 null이다
     * @return 동일 거래에서 잠근 부모·버전·현재 계정
     * @throws AuthException 인증·노출·편집 권한·상태·수정번호 검증 실패 시
     */
    private LockedVersion lockVersion(
            String sid, AdminActor actor, String code, int number, Long expected) {
        Account account = authorizeLocked(sid, actor);
        StoryRow story = story(code);
        permit(story, account, actor, false);
        VersionRow v = version(story.id, number);
        if (!v.active && story.owner != actor.accountId()) throw missing();
        if (expected != null) {
            permit(story, account, actor, true);
            if (!story.active || !v.active || !"DRAFT".equals(v.status))
                throw AuthException.conflict("STATE_CONFLICT");
            if (v.rev != expected) throw AuthException.conflict("EDIT_CONFLICT");
        }
        return new LockedVersion(story, v, account);
    }

    /**
     * 자식 실변경의 부모 수정번호를 한 번 올리고 필수 감사를 같은 트랜잭션에 기록한다.
     *
     * @param scope 현재 트랜잭션에서 잠근 버전이며 별도 작업에 재사용하지 않는다
     * @param actor 현재 잠금으로 확인한 서버 행위자
     * @param action 서버가 고정한 ITEM_* 행동 또는 CONTENT_READ
     * @param resource 서버에서 고정한 자식 콘텐츠·관계 자원명이며 grade-samples를 포함한다
     * @param key 원문이 아닌 검증된 ASCII 자식 키
     * @param fields 원문 대신 변경한 허용 필드명이며 조회이면 null이다
     * @param requestId 필수 감사에 연결할 null이 아닌 서버 요청 ID
     * @return 감사까지 성공한 현재 콘텐츠 수정번호
     */
    long recordChildChange(
            VersionScope scope,
            AdminActor actor,
            String resource,
            String action,
            String key,
            List<String> fields,
            UUID requestId) {
        if (!Set.of(
                        "persons",
                        "roles",
                        "pairs",
                        "clues",
                        "clue-roles",
                        "hints",
                        "events",
                        "facts",
                        "rubrics",
                        "rubric-clues",
                        "grade-samples")
                .contains(resource)) throw AuthException.badRequest("INVALID_REQUEST");
        boolean change = !"CONTENT_READ".equals(action);
        if (change && scope.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
        long after = scope.rev + (change ? 1 : 0);
        if (change) {
            db.update(
                    "UPDATE grade_sample SET checked_by=NULL WHERE version_id=? AND checked_by IS"
                            + " NOT NULL",
                    scope.versionId);
            db.update(
                    "UPDATE story_version SET edit_rev=?,updated_by=?,updated_at=clock_timestamp()"
                            + " WHERE id=?",
                    after,
                    actor.accountId(),
                    scope.versionId);
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

    /**
     * 명시적인 사람 확인의 표시·수정번호·필수 감사를 같은 거래에서 확정하며 콘텐츠는 보존한다.
     *
     * @param scope 현재 거래에서 잠그고 전체 예시를 검증한 버전이며 null이 아니다
     * @param actor 현재 편집 권한을 확인한 행위자이며 null이 아니다
     * @param count 전체 검증을 마친 활성 예시 수이며 1 이상이다
     * @param requestId 접근 이력에 연결할 서버 UUID이며 null이 아니다
     * @return 한 번 증가한 콘텐츠·검수 흐름 수정번호
     * @throws AuthException 수정번호 소진 또는 활성 예시 수 불일치 시; 감사 장애는 거래 전체를 되돌린다
     */
    long recordSampleCheck(VersionScope scope, AdminActor actor, int count, UUID requestId) {
        if (requestId == null || count <= 0) throw AuthException.badRequest("INVALID_REQUEST");
        if (scope.rev == Long.MAX_VALUE) throw AuthException.conflict("EDIT_CONFLICT");
        int updated =
                db.update(
                        "UPDATE grade_sample SET checked_by=?,updated_at=clock_timestamp()"
                                + " WHERE version_id=? AND active_yn",
                        actor.accountId(),
                        scope.versionId);
        if (updated != count) throw AuthException.conflict("EDIT_CONFLICT");

        long after = scope.rev + 1;
        db.update(
                "UPDATE story_version SET edit_rev=?,updated_by=?,updated_at=clock_timestamp()"
                        + " WHERE id=?",
                after,
                actor.accountId(),
                scope.versionId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("revisionScope", "CONTENT");
        detail.put("resource", "grade-samples");
        detail.put("checkedCount", count);
        insertAudit(
                scope.storyId, scope.versionId, actor, "SAMPLES_CHECKED", scope.rev, after, detail);
        return after;
    }

    /** 같은 거래의 현재 단서 배정과 채점표 점수를 다시 읽어 변경 후 경고를 구성한다. */
    List<Warning> currentWarnings(VersionScope scope) {
        List<Warning> result = new ArrayList<>();
        scope.warnings().stream()
                .filter(
                        w ->
                                !"REFERENCE_UNASSIGNED".equals(w.code())
                                        && !w.field().startsWith("rubrics."))
                .forEach(result::add);
        result.addAll(unassignedClues(scope.versionId()));
        result.addAll(StoryRubricService.rubricWarnings(db, scope.versionId()));
        return List.copyOf(result);
    }

    /** 활성 ROLE 단서 중 현재 활성 배정이 없는 코드만 경고한다. */
    private List<Warning> unassignedClues(long versionId) {
        return db.query(
                "SELECT c.code FROM story_clue c WHERE c.version_id=? AND c.active_yn AND"
                    + " c.scope='ROLE' AND NOT EXISTS (SELECT 1 FROM clue_role cr WHERE"
                    + " cr.version_id=c.version_id AND cr.clue_code=c.code AND cr.active_yn) ORDER"
                    + " BY c.code COLLATE \"C\"",
                (rs, row) -> new Warning("REFERENCE_UNASSIGNED", "clues." + rs.getString(1)),
                versionId);
    }

    /** 버전 원고와 현재 활성 단서·채점표의 경고를 결합한다. */
    private List<Warning> warningsWithClues(VersionRow version) {
        List<Warning> result = new ArrayList<>(warnings(version));
        result.addAll(unassignedClues(version.id()));
        result.addAll(StoryRubricService.rubricWarnings(db, version.id()));
        return List.copyOf(result);
    }

    /** 복제 서비스가 부모 경계와 같은 DataSource만 사용하도록 기존 JDBC 객체를 제공한다. */
    JdbcTemplate cloneDatabase() {
        return db;
    }

    /** 새 초안에 적용할 현재 제작 정책 코드이며 역사 사본의 정책을 덮어쓰지 않는다. */
    String clonePolicyCode() {
        return POLICY;
    }

    /**
     * 현재 계정·자격·세션→사건→전체 버전 ID 순서로 잠근 뒤 복제 작업을 실행한다.
     *
     * @param sid 현재 일반 세션 ID
     * @param actor 현재 인증 행위자이며 재인증·CREATE·REVIEW·PUBLISH는 요구하지 않는다
     * @param code 정규 사건 코드
     * @param work 같은 DataSource의 거래 안에서만 실행하는 복제 작업
     * @return 원고 없는 확정 결과 또는 현재 권한으로 재생한 영수증
     * @throws AuthException 현재 인증·EDIT 부족, 주변 거래, 잠금·저장 장애 시
     */
    <T> T withClone(
            String sid,
            AdminActor actor,
            String code,
            java.util.function.Function<CloneScope, T> work) {
        path(code, 1);
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw AuthException.unavailable("STORY_UNAVAILABLE");
        precheck(sid, actor);
        try {
            return batchTx.execute(
                    status -> {
                        Boolean valid =
                                db.execute(
                                        (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                                                connection ->
                                                        !connection.getAutoCommit()
                                                                && !connection.isReadOnly()
                                                                && connection
                                                                                .getTransactionIsolation()
                                                                        == java.sql.Connection
                                                                                .TRANSACTION_READ_COMMITTED);
                        if (!Boolean.TRUE.equals(valid))
                            throw AuthException.unavailable("STORY_UNAVAILABLE");
                        db.execute("SET LOCAL lock_timeout = '5s'");
                        lockAccounts(List.of(actor.accountId()));
                        Account account = authorizeLocked(sid, actor);
                        StoryRow story = story(code);
                        permit(story, account, actor, true);
                        db.queryForList(
                                "SELECT id FROM story_version WHERE story_id=? ORDER BY id FOR"
                                        + " UPDATE",
                                Long.class,
                                story.id);
                        return work.apply(
                                new CloneScope(
                                        story.id,
                                        story.owner,
                                        story.active,
                                        story.rev,
                                        story.published));
                    });
        } catch (DataAccessException failure) {
            String state = sqlState(failure);
            throw AuthException.unavailable(
                    "55P03".equals(state) || "40P01".equals(state)
                            ? "STORY_BUSY"
                            : "STORY_UNAVAILABLE");
        }
    }

    /**
     * 새 초안의 저장 필드와 자식을 기존 경고 규칙으로 읽되 상위 잠금을 다시 획득하지 않는다.
     *
     * @param versionId 같은 복제 거래에서 만든 실제 버전 ID
     * @return 콘텐츠를 자동 보정하지 않는 기존 코드·필드 경고
     */
    List<Warning> cloneWarnings(long versionId) {
        VersionRow row =
                db.queryForObject(
                        "SELECT"
                            + " id,edit_rev,status,title,intro,setting,difficulty,est_min,est_max,limit_sec,policy_code,culprit_code,method_answer,time_answer,motive_answer,timeline_origin,reveal_text,current_snapshot_id,active_yn,updated_at"
                            + " FROM story_version WHERE id=?",
                        (rs, index) -> mapVersion(rs),
                        versionId);
        return warningsWithClues(row);
    }

    /** 같은 부모 거래에서 확인한 현재 소유·활성·수정번호·공개 내부 ID다. */
    record CloneScope(long storyId, long ownerId, boolean active, long rev, Long publishedId) {}

    private void precheck(String sid, AdminActor actor) {
        if (!sessions.matchesStored(sid, actor)) throw AuthException.unauthorized("AUTH_REQUIRED");
    }

    private Account authorizeLocked(String sid, AdminActor actor) {
        db.execute("SET LOCAL lock_timeout = '5s'");
        Account account =
                db.query(
                        "SELECT"
                            + " a.id,a.account_key,a.active_yn,a.can_create,a.can_review,a.can_publish,c.auth_rev,c.enrolled_at,c.mfa_state"
                            + " FROM admin_account a JOIN admin_credential c ON c.account_id=a.id"
                            + " WHERE a.id=? FOR UPDATE OF a,c",
                        rs ->
                                rs.next()
                                        ? new Account(
                                                rs.getLong(1),
                                                (UUID) rs.getObject(2),
                                                rs.getBoolean(3),
                                                rs.getBoolean(4),
                                                rs.getBoolean(5),
                                                rs.getBoolean(6),
                                                rs.getLong(7),
                                                rs.getTimestamp(8) != null,
                                                rs.getString(9))
                                        : null,
                        actor.accountId());
        if (account == null
                || !account.active
                || !account.enrolled
                || !"READY".equals(account.mfaState)
                || account.rev != actor.authRev()
                || !account.key.equals(actor.accountKey()))
            throw AuthException.unauthorized("AUTH_REQUIRED");
        Integer live =
                db.query(
                        "SELECT 1 FROM admin_session WHERE session_key=? AND account_id=? AND"
                                + " sid_hash=? AND auth_rev=? AND state='ACTIVE' AND"
                                + " expires_at>clock_timestamp() AND last_action_at+interval '30"
                                + " minutes'>clock_timestamp() FOR UPDATE",
                        rs -> rs.next() ? rs.getInt(1) : null,
                        actor.sessionKey(),
                        actor.accountId(),
                        crypto.sessionHash(sid),
                        actor.authRev());
        if (live == null) throw AuthException.unauthorized("AUTH_REQUIRED");
        return account;
    }

    private StoryRow story(String code) {
        StoryRow row =
                db.query(
                        "SELECT id,owner_id,active_yn,edit_rev,published_id FROM story WHERE code=?"
                                + " FOR UPDATE",
                        rs ->
                                rs.next()
                                        ? new StoryRow(
                                                rs.getLong(1),
                                                rs.getLong(2),
                                                rs.getBoolean(3),
                                                rs.getLong(4),
                                                (Long) rs.getObject(5))
                                        : null,
                        code);
        if (row == null) throw missing();
        return row;
    }

    private VersionRow version(long storyId, int number) {
        VersionRow row =
                db.query(
                        "SELECT"
                            + " id,edit_rev,status,title,intro,setting,difficulty,est_min,est_max,limit_sec,policy_code,culprit_code,method_answer,time_answer,motive_answer,timeline_origin,reveal_text,current_snapshot_id,active_yn,updated_at"
                            + " FROM story_version WHERE story_id=? AND version_no=? FOR UPDATE",
                        rs -> rs.next() ? mapVersion(rs) : null,
                        storyId,
                        number);
        if (row == null) throw missing();
        return row;
    }

    private static VersionRow mapVersion(ResultSet rs) throws SQLException {
        return new VersionRow(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                smallint(rs, 7),
                smallint(rs, 8),
                smallint(rs, 9),
                (Integer) rs.getObject(10),
                rs.getString(11),
                rs.getString(12),
                rs.getString(13),
                rs.getString(14),
                rs.getString(15),
                rs.getString(16),
                rs.getString(17),
                (Long) rs.getObject(18),
                rs.getBoolean(19),
                rs.getTimestamp(20).toInstant());
    }

    private static Short smallint(ResultSet rs, int column) throws SQLException {
        short value = rs.getShort(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * 현재 제작 조회 범위를 먼저 확인하고 편집 요청에만 기존 EDIT 조건을 추가한다.
     *
     * @param s null이 아닌 잠긴 사건
     * @param a null이 아닌 같은 거래의 현재 인가 계정
     * @param actor null이 아닌 해당 계정에 결속된 서버 행위자
     * @param edit true이면 소유자 또는 활성 EDIT를 요구하며 상태 검사는 호출자가 수행한다
     * @throws AuthException 제작 조회 범위가 없으면 NOT_FOUND, 편집 권한만 없으면 FORBIDDEN
     */
    private void permit(StoryRow s, Account a, AdminActor actor, boolean edit) {
        if (s.owner == actor.accountId()) return;
        if (!s.active) throw missing();
        int count =
                db.queryForObject(
                        "SELECT count(*) FROM story_access WHERE story_id=? AND admin_id=? AND"
                            + " active_yn AND (permission='EDIT' OR (permission='REVIEW' AND ?) OR"
                            + " (permission='PUBLISH' AND ?))",
                        Integer.class,
                        s.id,
                        actor.accountId(),
                        a.review,
                        a.publish);
        if (count == 0) throw missing();
        if (edit && activeStoryPermissionCount(s.id, actor.accountId(), "EDIT") == 0)
            throw AuthException.forbidden("FORBIDDEN");
    }

    /**
     * 같은 사건·계정의 활성 작업 관계 수를 기존 인가와 응답 투영에 동일하게 제공한다.
     *
     * @param storyId 잠긴 사건의 양의 내부 ID
     * @param adminId 현재 계정의 양의 내부 ID
     * @param permission 서버가 고정한 EDIT/REVIEW/PUBLISH 중 하나이며 null이 아니다
     * @return 활성 관계 수이며 EDIT 존재와 엄격한 REVIEW/PUBLISH 단일 관계를 구분한다
     */
    private int activeStoryPermissionCount(long storyId, long adminId, String permission) {
        return db.queryForObject(
                "SELECT count(*) FROM story_access WHERE story_id=? AND admin_id=? AND"
                        + " permission=? AND active_yn",
                Integer.class,
                storyId,
                adminId,
                permission);
    }

    /**
     * 활성 부모와 현재 계정·관계만으로 행동 권한을 투영하며 실행 상태·품질은 판단하지 않는다.
     *
     * @param s null이 아닌 잠긴 사건
     * @param v null이 아닌 잠긴 소속 버전
     * @param a null이 아닌 최초 인가 잠금에서 읽은 현재 계정
     * @return 항상 존재하는 세 불리언이며 비활성 부모에서는 모두 false이다
     */
    private VersionPermissions permissions(StoryRow s, VersionRow v, Account a) {
        if (!s.active || !v.active) return new VersionPermissions(false, false, false);

        return new VersionPermissions(
                s.owner == a.id || activeStoryPermissionCount(s.id, a.id, "EDIT") > 0,
                a.review && activeStoryPermissionCount(s.id, a.id, "REVIEW") == 1,
                a.publish && activeStoryPermissionCount(s.id, a.id, "PUBLISH") == 1);
    }

    /**
     * 감사가 성공한 원고 상세에 같은 잠금 관찰의 행동 권한을 한 번 조립한다.
     *
     * @param s null이 아닌 잠긴 사건
     * @param v null이 아닌 잠긴 소속 버전
     * @param account null이 아닌 최초 인가 잠금에서 읽은 계정이며 응답에 직접 노출하지 않는다
     * @return 공개 키·원고·정책·경고·행동 권한만 포함한 상세
     */
    private VersionDetail detail(StoryRow s, VersionRow v, Account account) {
        UUID ownerKey =
                db.queryForObject(
                        "SELECT account_key FROM admin_account WHERE id=?", UUID.class, s.owner);
        Map<String, Object> f = fields(v);
        Map<String, Object> sections =
                Map.of(
                        "basic",
                        subset(f, BASIC),
                        "answer",
                        subset(f, ANSWER),
                        "reveal",
                        subset(f, REVEAL));
        return new VersionDetail(
                db.queryForObject("SELECT code FROM story WHERE id=?", String.class, s.id),
                Long.toString(s.rev),
                s.active,
                ownerKey,
                db.queryForObject(
                        "SELECT version_no FROM story_version WHERE id=?", Integer.class, v.id),
                Long.toString(v.rev),
                v.status,
                v.active,
                v.snapshot == null ? null : v.snapshot.toString(),
                permissions(s, v, account),
                sections,
                policy(v),
                warningsWithClues(v),
                v.updatedAt);
    }

    private static Map<String, Object> subset(Map<String, Object> all, Set<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        keys.stream().sorted().forEach(k -> result.put(k, all.get(k)));
        return result;
    }

    private static Map<String, Object> fields(VersionRow v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", v.title);
        m.put("intro", v.intro);
        m.put("setting", v.setting);
        m.put("difficulty", v.difficulty);
        m.put("estMin", v.estMin);
        m.put("estMax", v.estMax);
        m.put("limitSec", v.limitSec);
        m.put("timelineOrigin", v.timelineOrigin);
        m.put("culpritCode", v.culprit);
        m.put("methodAnswer", v.method);
        m.put("timeAnswer", v.time);
        m.put("motiveAnswer", v.motive);
        m.put("revealText", v.reveal);
        return m;
    }

    private static Policy policy(VersionRow v) {
        Integer d = v.difficulty == null ? null : v.difficulty.intValue();
        return new Policy(
                v.policy,
                d == null ? null : d <= 2 ? 5 : d <= 4 ? 3 : 2,
                d == null ? null : d <= 2 ? 3 : d <= 4 ? 2 : 1,
                10,
                Map.of("CULPRIT", 25, "METHOD", 20, "TIME", 15, "MOTIVE", 10, "EVIDENCE", 30));
    }

    private static List<Warning> warnings(VersionRow v) {
        List<Warning> result = new ArrayList<>();
        for (String field :
                List.of(
                        "intro",
                        "setting",
                        "culpritCode",
                        "methodAnswer",
                        "timeAnswer",
                        "motiveAnswer",
                        "revealText")) {
            Object value = fields(v).get(field);
            if (value == null || value instanceof String s && s.isBlank())
                result.add(
                        new Warning(
                                "MISSING_CONTENT",
                                (Set.of("intro", "setting").contains(field)
                                                ? "basic."
                                                : field.equals("revealText")
                                                        ? "reveal."
                                                        : "answer.")
                                        + field));
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
            if (!node.isIntegralNumber() || !node.canConvertToInt())
                throw AuthException.unprocessable("INVALID_INPUT");
            int n = node.intValue();
            if (key.equals("difficulty")
                    ? n < 1 || n > 5
                    : key.equals("limitSec") ? n <= 0 : n <= 0 || n > Short.MAX_VALUE)
                throw AuthException.unprocessable("INVALID_INPUT");
            if (key.equals("limitSec")) return Integer.valueOf(n);
            return Short.valueOf((short) n);
        }
        if (!node.isTextual()) throw AuthException.unprocessable("INVALID_INPUT");
        int max =
                switch (key) {
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
        if (key.equals("culpritCode") && !value.matches("[A-Z0-9_]{1,32}"))
            throw AuthException.unprocessable("INVALID_INPUT");
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

    private void audit(
            long storyId,
            Long versionId,
            AdminActor actor,
            String action,
            Long before,
            Long after,
            UUID requestId,
            String scope,
            List<String> fields) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("revisionScope", scope);
        if (fields != null) {
            detail.put("resource", "section");
            detail.put("changedFields", fields);
        }
        insertAudit(storyId, versionId, actor, action, before, after, detail);
    }

    /** 원문을 받지 않는 고정 감사 항목을 4 KiB 이내 JSON으로 저장하며 실패 시 업무를 되돌린다. */
    private void insertAudit(
            long storyId,
            Long versionId,
            AdminActor actor,
            String action,
            Long before,
            Long after,
            Map<String, Object> detail) {
        try {
            String serialized = json.writeValueAsString(detail);
            if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
                throw AuthException.unprocessable("INVALID_INPUT");
            db.update(
                    "INSERT INTO"
                        + " story_audit(story_id,version_id,actor_id,action,before_rev,after_rev,detail)"
                        + " VALUES (?,?,?,?,?,?,?::jsonb)",
                    storyId,
                    versionId,
                    actor == null ? null : actor.accountId(),
                    action,
                    before,
                    after,
                    serialized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    private <T> T transact(java.util.function.Supplier<T> action, String createConflict) {
        try {
            return tx.execute(status -> action.get());
        } catch (AuthException e) {
            throw e;
        } catch (DataAccessException e) {
            String state = sqlState(e);
            if ("55P03".equals(state) || "40P01".equals(state))
                throw AuthException.unavailable("STORY_BUSY");
            if (createConflict != null && "23505".equals(state))
                throw AuthException.conflict(createConflict);
            throw AuthException.unavailable("STORY_UNAVAILABLE");
        }
    }

    private static String sqlState(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof SQLException sql) return sql.getSQLState();
        return null;
    }

    private static void path(String code, int version) {
        if (code == null || !code.matches("[A-Z0-9_]{1,40}") || version <= 0)
            throw AuthException.badRequest("INVALID_REQUEST");
    }

    private static long revision(String raw) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*"))
            throw AuthException.badRequest("INVALID_REQUEST");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw AuthException.badRequest("INVALID_REQUEST");
        }
    }

    private static long positive(String raw) {
        long n = revision(raw);
        if (n == 0) throw AuthException.badRequest("INVALID_REQUEST");
        return n;
    }

    private static AuthException missing() {
        return new AuthException(404, "NOT_FOUND", "NOT_FOUND");
    }

    private record Account(
            long id,
            UUID key,
            boolean active,
            boolean create,
            boolean review,
            boolean publish,
            long rev,
            boolean enrolled,
            String mfaState) {}

    private record StoryRow(long id, long owner, boolean active, long rev, Long published) {}

    /** 동일 트랜잭션에서 확인한 부모·버전과 최초 인가의 현재 계정이며 모두 null이 아니다. */
    private record LockedVersion(StoryRow story, VersionRow version, Account account) {}

    /** 자식 서비스에 필요한 내부 식별자·수정번호만 전달하며 HTTP로 노출하지 않는다. */
    record VersionScope(
            long storyId, long versionId, long rev, String culprit, List<Warning> warnings) {}

    record AccessScope(
            long storyId, long rev, boolean active, long ownerId, UUID ownerKey, Long targetId) {}

    record OwnerScope(
            long storyId,
            long ownerId,
            UUID ownerKey,
            long rev,
            boolean active,
            Long recipientId,
            Long pendingRecipientId) {}

    private record PendingPart(long fromId, long toId) {}

    private record VersionRow(
            long id,
            long rev,
            String status,
            String title,
            String intro,
            String setting,
            Short difficulty,
            Short estMin,
            Short estMax,
            Integer limitSec,
            String policy,
            String culprit,
            String method,
            String time,
            String motive,
            String timelineOrigin,
            String reveal,
            Long snapshot,
            boolean active,
            Instant updatedAt) {}

    public record StoryCreated(
            String storyCode,
            String storyRev,
            int versionNo,
            String editRev,
            String status,
            UUID requestId) {}

    public record StorySummary(
            String storyCode,
            String storyRev,
            boolean activeYn,
            UUID ownerAccountKey,
            int versionNo,
            String editRev,
            String status,
            boolean versionActiveYn,
            String title,
            Short difficulty,
            Instant updatedAt) {}

    public record StoryPage(List<StorySummary> items, boolean hasNext, String nextAfterId) {}

    public record Policy(
            String policyCode,
            Integer attemptLimit,
            Integer hintsPerPerson,
            int wrongPenalty,
            Map<String, Integer> categoryScores) {}

    public record Warning(String code, String field) {}

    /**
     * 상태별 실행 가능성과 구분한 현재 행동 권한이며 계정·세션·내부 식별자를 노출하지 않는다.
     *
     * @param edit 활성 부모의 소유자 또는 활성 EDIT 관계이면 true
     * @param review 활성 부모의 현재 전역 REVIEW와 같은 사건 활성 REVIEW가 모두 있으면 true
     * @param publish 활성 부모의 현재 전역 PUBLISH와 같은 사건 활성 PUBLISH가 모두 있으면 true
     */
    public record VersionPermissions(boolean edit, boolean review, boolean publish) {}

    public record VersionDetail(
            String storyCode,
            String storyRev,
            boolean storyActiveYn,
            UUID ownerAccountKey,
            int versionNo,
            String editRev,
            String status,
            boolean activeYn,
            String currentSnapshotId,
            VersionPermissions permissions,
            Map<String, Object> sections,
            Policy policy,
            List<Warning> warnings,
            Instant updatedAt) {}

    public record ContentResult(
            String storyCode,
            int versionNo,
            String editRev,
            boolean changed,
            List<Warning> warnings,
            UUID requestId) {}

    public record StoryStateResult(
            String storyCode,
            String storyRev,
            boolean activeYn,
            boolean changed,
            String auditStatus,
            UUID requestId) {}
}
