package com.reasoning.common.grading.repository;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.auth.MemberPolicyEvidenceRegistry;
import com.reasoning.common.member.auth.MemberPolicyGate;
import com.reasoning.common.member.auth.PlaytestPolicyGate;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 실제 BATCH 루트 잠금과 신규 실행의 현재 출처 자격을 분리하는 경계다. */
@Repository
public class GradeSourceRepository {
    private static final String RELATIONSHIPS =
            """
            SELECT j.id AS job_id,j.batch_id,j.snapshot_id,j.runtime_id,
                b.created_by AS creator_id,b.runtime_id AS batch_runtime_id,
                b.snapshot_id AS batch_snapshot_id,s.version_id,v.story_id
            FROM public.grade_job j
            JOIN public.grade_batch b ON b.id=j.batch_id
            JOIN public.review_snapshot s ON s.id=b.snapshot_id
            JOIN public.story_version v ON v.id=s.version_id
            WHERE j.job_key=?
            """;
    private static final String VERSION_COLUMNS =
            "story_id,active_yn,status,current_snapshot_id,edit_rev,policy_code";
    private static final String RUNTIME_COLUMNS = "code,config_hash,state,epoch";
    private static final String BATCH_COLUMNS =
            "batch_key,snapshot_id,runtime_id,created_by,purpose,state,runtime_epoch,"
                    + "config_hash,rubric_hash,payload_hash,dataset_hash,created_at";
    private static final String JOB_COLUMNS =
            "job_key,batch_id,snapshot_id,runtime_id,state,config_hash,rubric_hash,"
                    + "input_hash,sample_code,repeat_no";
    private static final String TEST_RELATIONSHIPS =
            """
            SELECT j.id AS job_id,j.report_id,j.snapshot_id AS job_snapshot,j.runtime_id AS job_runtime,
                r.test_id,r.snapshot_id AS report_snapshot,r.runtime_id AS report_runtime,
                r.proposer_id,r.accepted_by,t.created_by,t.version_id,t.snapshot_id AS test_snapshot,
                t.runtime_id AS test_runtime,v.story_id,ret.policy_id,p.owner_id,p.env_code,
                proposer_profile.policy_id AS proposer_policy,acceptor_profile.policy_id AS acceptor_policy,
                proposer_policy.owner_id AS proposer_owner,acceptor_policy.owner_id AS acceptor_owner,
                proposer_identity.id AS proposer_identity,acceptor_identity.id AS acceptor_identity
            FROM public.grade_job j JOIN public.test_report r ON r.id=j.report_id
            JOIN public.play_test t ON t.id=r.test_id
            JOIN public.story_version v ON v.id=t.version_id
            JOIN public.test_retention ret ON ret.test_id=t.id
            JOIN public.privacy_policy p ON p.id=ret.policy_id
            JOIN public.member_profile proposer_profile ON proposer_profile.member_id=r.proposer_id
            JOIN public.member_profile acceptor_profile ON acceptor_profile.member_id=r.accepted_by
            JOIN public.privacy_policy proposer_policy ON proposer_policy.id=proposer_profile.policy_id
            JOIN public.privacy_policy acceptor_policy ON acceptor_policy.id=acceptor_profile.policy_id
            LEFT JOIN public.member_identity proposer_identity ON proposer_identity.member_id=r.proposer_id
                AND proposer_identity.provider='LOCAL' AND proposer_identity.realm='LOCAL'
                AND proposer_identity.active_yn
            LEFT JOIN public.member_identity acceptor_identity ON acceptor_identity.member_id=r.accepted_by
                AND acceptor_identity.provider='LOCAL' AND acceptor_identity.realm='LOCAL'
                AND acceptor_identity.active_yn
            WHERE j.job_key=? AND j.batch_id IS NULL
            """;
    private final JdbcTemplate jdbc;
    private final MemberPolicyGate memberPolicy;

    public record TestPreparation(
            PlaytestPolicyGate.Snapshot playtest, MemberPolicyEvidenceRegistry.Snapshot member) {}

    public TestPreparation prepareTest(PlaytestPolicyGate policy) {
        if (memberPolicy == null) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return new TestPreparation(policy.prepare(), memberPolicy.prepare());
    }

    /**
     * @param jdbc 호출자 트랜잭션과 같은 DataSource의 DB 도구, null 불가
     */
    public GradeSourceRepository(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GradeSourceRepository(JdbcTemplate jdbc, MemberPolicyGate memberPolicy) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.memberPolicy = memberPolicy;
    }

    /**
     * 호출자 소유 쓰기 READ COMMITTED 트랜잭션에서 실제 account → credential → runtime → story → version → batch →
     * job을 별도 문장으로 잠근다. 호출자는 하위 job/lease 잠금을 먼저 보유하지 않아야 한다. 발견 관계가 바뀌면 새 루트를 추가하지 않고 거절한다. 회수된
     * 출처와 종료된 작업도 실제 루트가 존재하면 잠근다. 반환값은 사실 증거이며 신규 실행 허가나 영수증 접근 권한이 아니다. 외부 호출·시도 생성·임대 변경·예산 소비를
     * 하지 않는다. 원문이나 비밀을 조회하지 않는다.
     *
     * @param jobKey 존재하는 BATCH 작업 UUID, null 불가
     * @return 이 저장소와 현재 연결 자원에 결속된 불변 사실 증거
     * @throws IllegalArgumentException jobKey가 null인 경우
     * @throws IllegalStateException 트랜잭션 조건 또는 실제 루트 관계가 잘못된 경우
     * @throws DataAccessResourceFailureException 민감한 원인을 제외한 고정 DB 실패
     */
    public LockedSource lockRoots(UUID jobKey) {
        if (jobKey == null) throw new IllegalArgumentException("INVALID_SOURCE_KEY");
        try {
            ConnectionHolder holder = requireTransaction();
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            Hints hints = discover(jobKey);
            Map<String, Object> account =
                    locked("admin_account", "id", hints.creatorId(), "active_yn,can_review");
            Map<String, Object> credential =
                    locked(
                            "admin_credential",
                            "account_id",
                            hints.creatorId(),
                            "enrolled_at,mfa_state");
            Map<String, Object> runtime =
                    locked("grade_runtime", "id", hints.batchRuntimeId(), RUNTIME_COLUMNS);
            Map<String, Object> story = locked("story", "id", hints.storyId(), "active_yn");
            Map<String, Object> version =
                    locked("story_version", "id", hints.versionId(), VERSION_COLUMNS);
            Map<String, Object> batch = locked("grade_batch", "id", hints.batchId(), BATCH_COLUMNS);
            Map<String, Object> job = locked("grade_job", "id", hints.jobId(), JOB_COLUMNS);
            if (!hints.equals(discover(jobKey)) || !jobKey.equals(job.get("job_key"))) reject();
            // 자원 holder가 재사용돼도 종료된 TX의 증거를 새 TX로 옮기지 못하게 한다.
            LockLifetime transaction = new LockLifetime();
            TransactionSynchronizationManager.registerSynchronization(transaction);
            return new LockedSource(
                    this,
                    holder,
                    transaction,
                    hints,
                    evidence(
                            hints,
                            jobKey,
                            runtime,
                            version,
                            batch,
                            job,
                            snapshot(hints),
                            createdAt(hints),
                            null),
                    (String) runtime.get("state"),
                    (String) batch.get("state"),
                    (String) job.get("state"),
                    Boolean.TRUE.equals(account.get("active_yn")),
                    Boolean.TRUE.equals(account.get("can_review")),
                    credential.get("enrolled_at") != null,
                    (String) credential.get("mfa_state"),
                    Boolean.TRUE.equals(story.get("active_yn")),
                    Boolean.TRUE.equals(version.get("active_yn")));
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("SOURCE_STORAGE_FAILURE");
        }
    }

    /**
     * 같은 호출자 트랜잭션의 실제 잠금 증거에 신규 실행의 현재 자격을 적용한다. 제한된 codec/manifest 검증 뒤 호출해야 한다. 잠근 행도 다시 읽어 같은
     * 트랜잭션의 변경을 과거 자격으로 재사용하지 않으며 매번 clock_timestamp()를 새로 읽는다. 현재 활성·등록 완료·MFA READY·전역 REVIEW·사건
     * REVIEW를 요구한다. 소유권·EDIT·MANAGE·최초 세션·최초 auth_rev로 대신하지 않는다. worker 인증/runtime 허용, 실제
     * 입력·dataset·rubric·config 해시 검증, lease/deadline fencing과 호출 예약은 별도 필수 단계다.
     *
     * @param source 이 저장소가 현재 트랜잭션에서 만든 사실 증거, null 불가
     * @return 현재 출처 자격을 검사한 메타데이터와 DB 실제 검사 시각, dispatch 허가는 아님
     * @throws IllegalArgumentException source가 null인 경우
     * @throws IllegalStateException 증거 소유권·수명·현재 자격 또는 24시간 효력이 잘못된 경우
     * @throws DataAccessResourceFailureException 민감한 원인을 제외한 고정 DB 실패
     */
    public RootEvidence requireExecutionEligibility(LockedSource source) {
        if (source == null) throw new IllegalArgumentException("INVALID_SOURCE_EVIDENCE");
        try {
            ConnectionHolder holder = requireTransaction();
            if (source.owner != this
                    || source.holder != holder
                    || !source.transaction.valid
                    || !TransactionSynchronizationManager.getSynchronizations()
                            .contains(source.transaction)) {
                throw new IllegalStateException("SOURCE_EVIDENCE_TRANSACTION_MISMATCH");
            }
            Hints hints = source.hints;
            UUID jobKey = source.jobKey();
            if (!hints.equals(discover(jobKey))) reject();
            Map<String, Object> account =
                    current("admin_account", "id", hints.creatorId(), "active_yn,can_review");
            Map<String, Object> credential =
                    current(
                            "admin_credential",
                            "account_id",
                            hints.creatorId(),
                            "enrolled_at,mfa_state");
            Map<String, Object> runtime =
                    current("grade_runtime", "id", hints.batchRuntimeId(), RUNTIME_COLUMNS);
            Map<String, Object> story = current("story", "id", hints.storyId(), "active_yn");
            Map<String, Object> version =
                    current("story_version", "id", hints.versionId(), VERSION_COLUMNS);
            Map<String, Object> batch =
                    current("grade_batch", "id", hints.batchId(), BATCH_COLUMNS);
            Map<String, Object> job = current("grade_job", "id", hints.jobId(), JOB_COLUMNS);
            if (!jobKey.equals(job.get("job_key"))) reject();
            Boolean review =
                    jdbc.queryForObject(
                            """
                            SELECT EXISTS(SELECT 1 FROM public.story_access
                                WHERE story_id=? AND admin_id=? AND permission='REVIEW' AND active_yn)
                            """,
                            Boolean.class,
                            hints.storyId(),
                            hints.creatorId());
            if (!Boolean.TRUE.equals(account.get("active_yn"))
                    || !Boolean.TRUE.equals(account.get("can_review"))
                    || credential.get("enrolled_at") == null
                    || !"READY".equals(credential.get("mfa_state"))
                    || !Boolean.TRUE.equals(review)) reject();
            String status = (String) version.get("status");
            String purpose = (String) batch.get("purpose");
            if (!Boolean.TRUE.equals(story.get("active_yn"))
                    || !Boolean.TRUE.equals(version.get("active_yn"))
                    || !Objects.equals(version.get("current_snapshot_id"), hints.snapshotId())
                    || hints.snapshotId() != hints.batchSnapshotId()
                    || hints.runtimeId() != hints.batchRuntimeId()
                    || !"RUNNING".equals(batch.get("state"))
                    || !"AVAILABLE".equals(runtime.get("state"))
                    || !runtime.get("epoch").equals(batch.get("runtime_epoch"))
                    || !runtime.get("config_hash").equals(batch.get("config_hash"))
                    || !runtime.get("config_hash").equals(job.get("config_hash"))
                    || !batch.get("rubric_hash").equals(job.get("rubric_hash"))
                    || !("STAGED".equals(job.get("state"))
                            || "QUEUED".equals(job.get("state"))
                            || "RUNNING".equals(job.get("state")))
                    || !("REVIEW".equals(status)
                            || ("AVAILABILITY".equals(purpose)
                                    && ("READY".equals(status) || "PUBLISHED".equals(status)))))
                reject();
            Map<String, Object> snapshot = snapshot(hints);
            OffsetDateTime created = createdAt(hints);
            OffsetDateTime now =
                    jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            if (now == null || created == null || !now.isBefore(created.plusHours(24))) {
                throw new IllegalStateException("BATCH_EXPIRED");
            }
            return evidence(hints, jobKey, runtime, version, batch, job, snapshot, created, now);
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("SOURCE_STORAGE_FAILURE");
        }
    }

    /**
     * 준비된 PLAYTEST 정책 근거와 실제 TEST 부모를 한 쓰기 거래에서 정렬 잠근다. JOIN은 발견에만 쓰며 관계가 잠금 사이 바뀌면 새로운 부모를 잠그지 않고
     * 거절한다. 작업자 인증과 임대 승인은 이 API가 하지 않는다.
     *
     * @param jobKey 실제 TEST 작업 키, null 불가
     * @param policy 정책 파일 증거를 검증하는 기존 PLAYTEST 경계, null 불가
     * @param prepared 거래 전에 준비한 PLAYTEST·MEMBER_AUTH 증거, null 불가
     * @return 현재 연결과 거래에 결속된 비공개 TEST 잠금 증거
     */
    public LockedTestSource lockTestRoots(
            UUID jobKey, PlaytestPolicyGate policy, TestPreparation prepared) {
        if (jobKey == null || policy == null || prepared == null)
            throw new IllegalArgumentException("INVALID_TEST_SOURCE");
        if (memberPolicy == null || prepared.member() == null) reject();
        return lockTestRoots(jobKey, policy, prepared.playtest(), prepared.member(), false);
    }

    /**
     * 종료·회수 판단만 위한 TEST 역사 루트를 잠근다. 현재 정책 파일·ACTIVE 상태·동의를 승인하지 않으며 이 증거로 신규 실행 자격을 획득할 수 없다.
     *
     * @param jobKey 실제 TEST 작업 키, null 불가
     * @return 같은 거래의 실제 역사 행에 결속된 비실행 잠금 증거
     */
    public LockedTestSource lockTestRootsForSettlement(UUID jobKey) {
        if (jobKey == null) throw new IllegalArgumentException("INVALID_TEST_SOURCE");
        return lockTestRoots(jobKey, null, null, null, true);
    }

    private LockedTestSource lockTestRoots(
            UUID jobKey,
            PlaytestPolicyGate policy,
            PlaytestPolicyGate.Snapshot prepared,
            MemberPolicyEvidenceRegistry.Snapshot memberPrepared,
            boolean historical) {
        try {
            ConnectionHolder holder = requireTransaction();
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            TestHints hints = discoverTest(jobKey);
            if (hints.proposerId() == hints.acceptorId()
                    || (hints.proposerPolicy() == hints.acceptorPolicy()
                            && hints.proposerOwner() != hints.acceptorOwner())
                    || (!historical
                            && (hints.policyOwner() != prepared.owner()
                                    || !hints.environment().equals(prepared.env())))) reject();
            TreeSet<Long> owners = new TreeSet<>();
            owners.add(hints.policyOwner());
            owners.add(hints.creatorId());
            owners.add(hints.proposerOwner());
            owners.add(hints.acceptorOwner());
            if (!historical) {
                owners.addAll(memberPolicy.ownerIds(hints.proposerId()));
                owners.addAll(memberPolicy.ownerIds(hints.acceptorId()));
            }
            for (long owner : owners)
                locked("admin_account", "id", owner, "active_yn,can_manage,can_review");
            long first = Math.min(hints.proposerId(), hints.acceptorId());
            long second = Math.max(hints.proposerId(), hints.acceptorId());
            locked("member_account", "id", first, "state");
            locked("member_account", "id", second, "state");
            for (long member : new long[] {first, second}) {
                if (historical) {
                    // 역사 정산도 실제 LOCAL 부모를 잠그지만 회수된 인증을 실행 허가로 쓰지 않는다.
                    var identities =
                            jdbc.queryForList(
                                    "SELECT id,member_id FROM public.member_identity WHERE"
                                            + " member_id=? AND provider='LOCAL' AND realm='LOCAL'"
                                            + " ORDER BY id FOR UPDATE",
                                    member);
                    if (identities.isEmpty()) reject();
                    for (var identity : identities)
                        if (number(identity, "member_id") != member) reject();
                    continue;
                }
                Long identityId =
                        member == hints.proposerId()
                                ? hints.proposerIdentity()
                                : hints.acceptorIdentity();
                if (!historical && identityId == null) reject();
                if (identityId != null) {
                    Map<String, Object> identity =
                            locked(
                                    "member_identity",
                                    "id",
                                    identityId,
                                    "member_id,active_yn,proof_at");
                    if (number(identity, "member_id") != member
                            || (!historical
                                    && (!Boolean.TRUE.equals(identity.get("active_yn"))
                                            || identity.get("proof_at") == null))) reject();
                }
            }
            for (long member : new long[] {first, second}) {
                Map<String, Object> profile =
                        locked("member_profile", "member_id", member, "policy_id");
                if (number(profile, "policy_id")
                        != (member == hints.proposerId()
                                ? hints.proposerPolicy()
                                : hints.acceptorPolicy())) reject();
            }
            for (long owner : owners)
                locked("admin_credential", "account_id", owner, "enrolled_at,mfa_state");
            java.util.List<MemberPolicyGate.Permit> permits = new java.util.ArrayList<>();
            if (!historical) {
                for (long member : new long[] {first, second}) {
                    var permit = memberPolicy.lock(memberPrepared, member, owners);
                    memberPolicy.checkPinned(permit, member);
                    memberPolicy.check(permit);
                    permits.add(permit);
                }
            }
            TreeSet<Long> policies = new TreeSet<>();
            policies.add(hints.proposerPolicy());
            policies.add(hints.acceptorPolicy());
            for (long id : policies) {
                Map<String, Object> pinned =
                        locked(
                                "privacy_policy",
                                "id",
                                id,
                                "owner_id,env_code,scope,state,notice_hash");
                long owner =
                        id == hints.proposerPolicy()
                                ? hints.proposerOwner()
                                : hints.acceptorOwner();
                if (number(pinned, "owner_id") != owner
                        || !"MEMBER_AUTH".equals(pinned.get("scope"))) reject();
            }
            Map<String, Object> pinnedPolicy =
                    locked(
                            "privacy_policy",
                            "id",
                            hints.policyId(),
                            "owner_id,env_code,scope,state,notice_hash");
            if (number(pinnedPolicy, "owner_id") != hints.policyOwner()
                    || !hints.environment().equals(pinnedPolicy.get("env_code"))
                    || !"PLAYTEST".equals(pinnedPolicy.get("scope"))) reject();
            PlaytestPolicyGate.Notice notice;
            if (historical) {
                notice = null;
            } else {
                notice = policy.lock(prepared, Set.of(hints.policyOwner()));
                if (notice.policyId() != hints.policyId()) reject();
            }
            locked("grade_runtime", "id", hints.testRuntime(), RUNTIME_COLUMNS);
            locked("story", "id", hints.storyId(), "active_yn");
            locked("story_version", "id", hints.versionId(), VERSION_COLUMNS);
            locked(
                    "play_test",
                    "id",
                    hints.testId(),
                    "test_key,version_id,snapshot_id,runtime_id,config_hash,runtime_epoch,draft_rev,state,mode,deadline_at");
            locked("test_retention", "test_id", hints.testId(), "policy_id,raw_until");
            // PlaytestAccess와 같이 참가 동의 자식은 테스트 및 보관 부모 뒤에 잠근다.
            lockedMember(hints.testId(), first);
            lockedMember(hints.testId(), second);
            locked(
                    "test_report",
                    "id",
                    hints.reportId(),
                    "test_id,snapshot_id,runtime_id,config_hash,runtime_epoch,source_draft_rev,proposer_id,accepted_by,accepted_at,submit_no,state,payload_hash,purged_at");
            locked(
                    "grade_job",
                    "id",
                    hints.jobId(),
                    "job_key,batch_id,report_id,report_hash,snapshot_id,runtime_id,state,config_hash,rubric_hash,input_hash");
            if (!hints.equals(discoverTest(jobKey))) reject();
            LockLifetime lifetime = new LockLifetime();
            TransactionSynchronizationManager.registerSynchronization(lifetime);
            return new LockedTestSource(
                    this,
                    holder,
                    lifetime,
                    hints,
                    jobKey,
                    prepared,
                    notice,
                    java.util.List.copyOf(permits));
        } catch (AuthException failure) {
            throw new IllegalStateException("SOURCE_NOT_CURRENT");
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("SOURCE_STORAGE_FAILURE");
        }
    }

    /**
     * 같은 거래의 실제 TEST 잠금과 현재·고정 회원 정책, LOCAL 증명, 양쪽 동의·원고·런타임·보고서 해시 결속을 다시 읽는다. 암호문 진위와 설치 검증,
     * worker 권한, lease 및 dispatch는 소비자가 별도로 검사한다.
     *
     * @param source 이 저장소가 현재 거래에서 발행한 TEST 증거, null 불가
     * @return 현재 출처 메타데이터; 실행 예약 권한은 아님
     */
    public TestRootEvidence requireTestExecutionEligibility(LockedTestSource source) {
        if (source == null) throw new IllegalArgumentException("INVALID_TEST_SOURCE");
        try {
            if (!validTestLifetime(source) || source.prepared == null || source.notice == null)
                reject();
            TestHints h = source.hints;
            if (!h.equals(discoverTest(source.jobKey))) reject();
            if (source.memberPermits.size() != 2) reject();
            long[] members = {
                Math.min(h.proposerId(), h.acceptorId()), Math.max(h.proposerId(), h.acceptorId())
            };
            for (int i = 0; i < members.length; i++) {
                memberPolicy.checkPinned(source.memberPermits.get(i), members[i]);
                memberPolicy.check(source.memberPermits.get(i));
                Long identityId =
                        members[i] == h.proposerId() ? h.proposerIdentity() : h.acceptorIdentity();
                if (identityId == null) reject();
                var identity =
                        current(
                                "member_identity",
                                "id",
                                identityId,
                                "member_id,provider,realm,active_yn,proof_at");
                if (number(identity, "member_id") != members[i]
                        || !"LOCAL".equals(identity.get("provider"))
                        || !"LOCAL".equals(identity.get("realm"))
                        || !Boolean.TRUE.equals(identity.get("active_yn"))
                        || identity.get("proof_at") == null) reject();
            }
            Map<String, Object> runtime =
                    current("grade_runtime", "id", h.testRuntime(), RUNTIME_COLUMNS);
            Map<String, Object> story = current("story", "id", h.storyId(), "active_yn");
            Map<String, Object> version =
                    current("story_version", "id", h.versionId(), VERSION_COLUMNS);
            Map<String, Object> test =
                    current(
                            "play_test",
                            "id",
                            h.testId(),
                            "test_key,version_id,snapshot_id,runtime_id,config_hash,runtime_epoch,draft_rev,state,mode,deadline_at");
            Map<String, Object> retention =
                    current("test_retention", "test_id", h.testId(), "policy_id,raw_until");
            Map<String, Object> report =
                    current(
                            "test_report",
                            "id",
                            h.reportId(),
                            "test_id,snapshot_id,runtime_id,config_hash,runtime_epoch,source_draft_rev,proposer_id,accepted_by,accepted_at,submit_no,state,payload_hash,purged_at");
            Map<String, Object> job =
                    current(
                            "grade_job",
                            "id",
                            h.jobId(),
                            "job_key,batch_id,report_id,report_hash,snapshot_id,runtime_id,state,config_hash,rubric_hash,input_hash,accepted_at,deadline_at");
            Map<String, Object> snap = snapshotTest(h);
            Map<String, Object> policyOwner =
                    current("admin_account", "id", h.policyOwner(), "active_yn,can_manage");
            Map<String, Object> credential =
                    current(
                            "admin_credential",
                            "account_id",
                            h.policyOwner(),
                            "enrolled_at,mfa_state");
            Map<String, Object> creator =
                    current("admin_account", "id", h.creatorId(), "active_yn,can_review");
            Map<String, Object> creatorCredential =
                    current(
                            "admin_credential",
                            "account_id",
                            h.creatorId(),
                            "enrolled_at,mfa_state");
            Map<String, Object> proposer = current("member_account", "id", h.proposerId(), "state");
            Map<String, Object> acceptor = current("member_account", "id", h.acceptorId(), "state");
            Map<String, Object> first = currentMember(h.testId(), h.proposerId());
            Map<String, Object> second = currentMember(h.testId(), h.acceptorId());
            var active =
                    jdbc.queryForList(
                            "SELECT id,owner_id,notice_hash,state FROM public.privacy_policy WHERE"
                                    + " env_code=? AND scope='PLAYTEST' AND state='ACTIVE'",
                            source.prepared.env());
            OffsetDateTime now =
                    jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            if (active.size() != 1
                    || now == null
                    || now.toInstant().isBefore(source.prepared.from())
                    || !now.toInstant().isBefore(source.prepared.until())
                    || number(active.getFirst(), "id") != h.policyId()
                    || number(active.getFirst(), "owner_id") != h.policyOwner()
                    || !source.notice.hash().equals(active.getFirst().get("notice_hash"))
                    || !Boolean.TRUE.equals(policyOwner.get("active_yn"))
                    || !Boolean.TRUE.equals(policyOwner.get("can_manage"))
                    || credential.get("enrolled_at") == null
                    || !"READY".equals(credential.get("mfa_state"))
                    || !Boolean.TRUE.equals(creator.get("active_yn"))
                    || !Boolean.TRUE.equals(creator.get("can_review"))
                    || creatorCredential.get("enrolled_at") == null
                    || !"READY".equals(creatorCredential.get("mfa_state"))
                    || !Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "SELECT EXISTS(SELECT 1 FROM public.story_access WHERE"
                                        + " story_id=? AND admin_id=? AND permission='REVIEW' AND"
                                        + " active_yn)",
                                    Boolean.class,
                                    h.storyId(),
                                    h.creatorId()))
                    || !"ACTIVE".equals(proposer.get("state"))
                    || !"ACTIVE".equals(acceptor.get("state"))
                    || !consented(first, source.notice)
                    || !consented(second, source.notice)
                    || number(retention, "policy_id") != h.policyId()
                    || (retention.get("raw_until") != null
                            && !now.toInstant()
                                    .isBefore(
                                            ((java.sql.Timestamp) retention.get("raw_until"))
                                                    .toInstant()))
                    || !Boolean.TRUE.equals(story.get("active_yn"))
                    || !Boolean.TRUE.equals(version.get("active_yn"))
                    || !"REVIEW".equals(version.get("status"))
                    || !Objects.equals(version.get("current_snapshot_id"), h.testSnapshot())
                    || number(version, "story_id") != h.storyId()
                    || number(test, "version_id") != h.versionId()
                    || number(test, "snapshot_id") != h.testSnapshot()
                    || number(test, "runtime_id") != h.testRuntime()
                    || !"RUNNING".equals(test.get("state"))
                    || test.get("deadline_at") == null
                    || report.get("accepted_at") == null
                    || !((java.sql.Timestamp) report.get("accepted_at"))
                            .toInstant()
                            .isBefore(((java.sql.Timestamp) test.get("deadline_at")).toInstant())
                    || !"AVAILABLE".equals(runtime.get("state"))
                    || !Objects.equals(test.get("config_hash"), runtime.get("config_hash"))
                    || !Objects.equals(test.get("runtime_epoch"), runtime.get("epoch"))
                    || !Objects.equals(report.get("config_hash"), runtime.get("config_hash"))
                    || !Objects.equals(report.get("runtime_epoch"), runtime.get("epoch"))
                    || number(report, "source_draft_rev") != number(test, "draft_rev")
                    || !"ACCEPTED".equals(report.get("state"))
                    || report.get("submit_no") == null
                    || report.get("purged_at") != null
                    || report.get("payload_hash") == null
                    || number(report, "test_id") != h.testId()
                    || number(report, "snapshot_id") != h.testSnapshot()
                    || number(report, "runtime_id") != h.testRuntime()
                    || number(report, "proposer_id") != h.proposerId()
                    || number(report, "accepted_by") != h.acceptorId()
                    || job.get("batch_id") != null
                    || number(job, "report_id") != h.reportId()
                    || number(job, "snapshot_id") != h.testSnapshot()
                    || number(job, "runtime_id") != h.testRuntime()
                    || !source.jobKey.equals(job.get("job_key"))
                    || !Objects.equals(job.get("accepted_at"), report.get("accepted_at"))
                    || job.get("deadline_at") == null
                    || !((java.sql.Timestamp) job.get("deadline_at"))
                            .toInstant()
                            .equals(
                                    ((java.sql.Timestamp) report.get("accepted_at"))
                                            .toInstant()
                                            .plusSeconds(120))
                    || !now.toInstant()
                            .isBefore(((java.sql.Timestamp) job.get("deadline_at")).toInstant())
                    || !Objects.equals(job.get("config_hash"), runtime.get("config_hash"))
                    || !("STAGED".equals(job.get("state"))
                            || "QUEUED".equals(job.get("state"))
                            || "RUNNING".equals(job.get("state")))) reject();
            return new TestRootEvidence(
                    h.jobId(),
                    source.jobKey,
                    h.reportId(),
                    h.testId(),
                    h.storyId(),
                    h.versionId(),
                    h.testSnapshot(),
                    h.testRuntime(),
                    (String) runtime.get("code"),
                    (String) runtime.get("config_hash"),
                    number(runtime, "epoch"),
                    number(snap, "edit_rev"),
                    number(snap, "format_no"),
                    (String) version.get("policy_code"),
                    (String) report.get("payload_hash"),
                    (String) job.get("report_hash"),
                    (String) job.get("rubric_hash"),
                    number(report, "source_draft_rev"),
                    now);
        } catch (AuthException failure) {
            throw new IllegalStateException("SOURCE_NOT_CURRENT");
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("SOURCE_STORAGE_FAILURE");
        }
    }

    /**
     * 같은 연결의 이미 잠근 TEST 부모에서 역사적 사실만 다시 읽는다. 종료·정책 회수·원문 파기에도 replay 식별이 가능하지만 현재 실행 자격이나 보고서 무결성을
     * 주장하지 않는다.
     *
     * @param source 이 저장소에서 발행한 현재 거래의 잠금 증거
     * @param dataSource 호출자 JDBC와 동일한 DataSource 객체
     * @return 원문 없는 물리 관계와 선언 해시; 실행 허가가 아님
     */
    private TestRootEvidence lockedTestFacts(
            LockedTestSource source, javax.sql.DataSource dataSource) {
        if (source == null
                || dataSource == null
                || jdbc.getDataSource() != dataSource
                || !validTestLifetime(source)) reject();
        try {
            TestHints h = source.hints;
            if (!h.equals(discoverTest(source.jobKey))) reject();
            Map<String, Object> runtime =
                    current("grade_runtime", "id", h.testRuntime(), RUNTIME_COLUMNS);
            Map<String, Object> version =
                    current("story_version", "id", h.versionId(), VERSION_COLUMNS);
            Map<String, Object> test =
                    current(
                            "play_test",
                            "id",
                            h.testId(),
                            "version_id,snapshot_id,runtime_id,config_hash,runtime_epoch");
            Map<String, Object> retention =
                    current("test_retention", "test_id", h.testId(), "policy_id");
            Map<String, Object> report =
                    current(
                            "test_report",
                            "id",
                            h.reportId(),
                            "test_id,snapshot_id,runtime_id,config_hash,runtime_epoch,source_draft_rev,proposer_id,accepted_by,payload_hash");
            Map<String, Object> job =
                    current(
                            "grade_job",
                            "id",
                            h.jobId(),
                            "job_key,batch_id,report_id,snapshot_id,runtime_id,config_hash,rubric_hash,report_hash");
            Map<String, Object> snapshot = snapshotTest(h);
            if (number(version, "story_id") != h.storyId()
                    || number(test, "version_id") != h.versionId()
                    || number(test, "snapshot_id") != h.testSnapshot()
                    || number(test, "runtime_id") != h.testRuntime()
                    || number(retention, "policy_id") != h.policyId()
                    || number(report, "test_id") != h.testId()
                    || number(report, "snapshot_id") != h.testSnapshot()
                    || number(report, "runtime_id") != h.testRuntime()
                    || number(report, "proposer_id") != h.proposerId()
                    || number(report, "accepted_by") != h.acceptorId()
                    || job.get("batch_id") != null
                    || number(job, "report_id") != h.reportId()
                    || number(job, "snapshot_id") != h.testSnapshot()
                    || number(job, "runtime_id") != h.testRuntime()
                    || !source.jobKey.equals(job.get("job_key"))
                    || !Objects.equals(test.get("config_hash"), report.get("config_hash"))
                    || !Objects.equals(test.get("config_hash"), job.get("config_hash"))
                    || !Objects.equals(test.get("runtime_epoch"), report.get("runtime_epoch")))
                reject();
            OffsetDateTime now =
                    jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            if (now == null) reject();
            return new TestRootEvidence(
                    h.jobId(),
                    source.jobKey,
                    h.reportId(),
                    h.testId(),
                    h.storyId(),
                    h.versionId(),
                    h.testSnapshot(),
                    h.testRuntime(),
                    (String) runtime.get("code"),
                    (String) job.get("config_hash"),
                    number(test, "runtime_epoch"),
                    number(snapshot, "edit_rev"),
                    number(snapshot, "format_no"),
                    (String) version.get("policy_code"),
                    (String) report.get("payload_hash"),
                    (String) job.get("report_hash"),
                    (String) job.get("rubric_hash"),
                    number(report, "source_draft_rev"),
                    now);
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("SOURCE_STORAGE_FAILURE");
        }
    }

    private TestHints discoverTest(UUID key) {
        var rows =
                jdbc.query(
                        TEST_RELATIONSHIPS,
                        (rs, n) ->
                                new TestHints(
                                        rs.getLong("job_id"),
                                        rs.getLong("report_id"),
                                        rs.getLong("test_id"),
                                        rs.getLong("proposer_id"),
                                        rs.getLong("accepted_by"),
                                        rs.getLong("created_by"),
                                        rs.getLong("story_id"),
                                        rs.getLong("version_id"),
                                        rs.getLong("test_snapshot"),
                                        rs.getLong("test_runtime"),
                                        rs.getLong("policy_id"),
                                        rs.getLong("owner_id"),
                                        rs.getString("env_code"),
                                        rs.getLong("job_snapshot"),
                                        rs.getLong("job_runtime"),
                                        rs.getLong("report_snapshot"),
                                        rs.getLong("report_runtime"),
                                        rs.getLong("proposer_policy"),
                                        rs.getLong("acceptor_policy"),
                                        rs.getLong("proposer_owner"),
                                        rs.getLong("acceptor_owner"),
                                        (Long) rs.getObject("proposer_identity"),
                                        (Long) rs.getObject("acceptor_identity")),
                        key);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        TestHints h = rows.getFirst();
        var row =
                jdbc.queryForMap(
                        "SELECT snapshot_id,runtime_id,batch_id FROM public.grade_job WHERE id=?",
                        h.jobId());
        if (row.get("batch_id") != null
                || number(row, "snapshot_id") != h.testSnapshot()
                || number(row, "runtime_id") != h.testRuntime()
                || h.jobSnapshot() != h.testSnapshot()
                || h.jobRuntime() != h.testRuntime()
                || h.reportSnapshot() != h.testSnapshot()
                || h.reportRuntime() != h.testRuntime()) reject();
        return h;
    }

    private Map<String, Object> lockedMember(long testId, long memberId) {
        var rows =
                jdbc.queryForList(
                        "SELECT invite_state,accepted_policy_id,accepted_notice_hash,revoked_at"
                            + " FROM public.test_member WHERE test_id=? AND member_id=? FOR UPDATE",
                        testId,
                        memberId);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    private Map<String, Object> currentMember(long testId, long memberId) {
        var rows =
                jdbc.queryForList(
                        "SELECT invite_state,accepted_policy_id,accepted_notice_hash,revoked_at"
                                + " FROM public.test_member WHERE test_id=? AND member_id=?",
                        testId,
                        memberId);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    private static boolean consented(Map<String, Object> member, PlaytestPolicyGate.Notice notice) {
        return "ACCEPTED".equals(member.get("invite_state"))
                && member.get("revoked_at") == null
                && Objects.equals(member.get("accepted_policy_id"), notice.policyId())
                && Objects.equals(member.get("accepted_notice_hash"), notice.hash());
    }

    private Map<String, Object> snapshotTest(TestHints h) {
        Map<String, Object> snapshot =
                jdbc.queryForMap(
                        "SELECT version_id,edit_rev,format_no FROM public.review_snapshot WHERE"
                                + " id=?",
                        h.testSnapshot());
        if (number(snapshot, "version_id") != h.versionId()) reject();
        return snapshot;
    }

    /** TEST 증명은 저장소·연결·동기화 수명 및 저장점 롤백에 결속한다. */
    private boolean validTestLifetime(LockedTestSource source) {
        return source != null
                && source.owner == this
                && source.holder == requireTransaction()
                && source.lifetime.valid
                && TransactionSynchronizationManager.getSynchronizations()
                        .contains(source.lifetime);
    }

    private record TestHints(
            long jobId,
            long reportId,
            long testId,
            long proposerId,
            long acceptorId,
            long creatorId,
            long storyId,
            long versionId,
            long testSnapshot,
            long testRuntime,
            long policyId,
            long policyOwner,
            String environment,
            long jobSnapshot,
            long jobRuntime,
            long reportSnapshot,
            long reportRuntime,
            long proposerPolicy,
            long acceptorPolicy,
            long proposerOwner,
            long acceptorOwner,
            Long proposerIdentity,
            Long acceptorIdentity) {}

    /** 외부 생성 불가·원문 없는 거래 전용 TEST 증거다. */
    public static final class LockedTestSource {
        private final GradeSourceRepository owner;
        private final ConnectionHolder holder;
        private final LockLifetime lifetime;
        private final TestHints hints;
        private final UUID jobKey;
        private final PlaytestPolicyGate.Snapshot prepared;
        private final PlaytestPolicyGate.Notice notice;
        private final java.util.List<MemberPolicyGate.Permit> memberPermits;

        private LockedTestSource(
                GradeSourceRepository owner,
                ConnectionHolder holder,
                LockLifetime lifetime,
                TestHints hints,
                UUID jobKey,
                PlaytestPolicyGate.Snapshot prepared,
                PlaytestPolicyGate.Notice notice,
                java.util.List<MemberPolicyGate.Permit> memberPermits) {
            this.owner = owner;
            this.holder = holder;
            this.lifetime = lifetime;
            this.hints = hints;
            this.jobKey = jobKey;
            this.prepared = prepared;
            this.notice = notice;
            this.memberPermits = memberPermits;
        }

        public long reportId() {
            return hints.reportId();
        }

        public long testId() {
            return hints.testId();
        }

        public long snapshotId() {
            return hints.testSnapshot();
        }

        public long versionId() {
            return hints.versionId();
        }

        public long storyId() {
            return hints.storyId();
        }

        public UUID jobKey() {
            return jobKey;
        }

        /** 실제 잠금 거래의 역사 사실만 반환하며 종료 상태나 회수된 출처에 실행 권한을 주지 않는다. */
        public TestRootEvidence lockedFacts(javax.sql.DataSource dataSource) {
            return owner.lockedTestFacts(this, dataSource);
        }

        /** 저장소 소유권과 현재 거래 수명을 다시 검증한 출처만 입력 저장소에 제공한다. */
        public TestRootEvidence requireCurrent(javax.sql.DataSource dataSource) {
            if (dataSource == null || owner.jdbc.getDataSource() != dataSource) reject();
            return owner.requireTestExecutionEligibility(this);
        }
    }

    /** 저장 해시는 암호문 검증 전 선언값이며 실행 허가가 아니다. */
    public record TestRootEvidence(
            long jobId,
            UUID jobKey,
            long reportId,
            long testId,
            long storyId,
            long versionId,
            long snapshotId,
            long runtimeId,
            String runtimeCode,
            String configHash,
            long runtimeEpoch,
            long snapshotRev,
            long snapshotFormat,
            String policyCode,
            String payloadHash,
            String reportHash,
            String rubricHash,
            long sourceDraftRev,
            OffsetDateTime checkedAt) {}

    /** 실제 호출자 연결 자원과 쓰기 READ COMMITTED 조건을 확인한다. */
    private ConnectionHolder requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        }
        Object resource =
                TransactionSynchronizationManager.getResource(
                        Objects.requireNonNull(jdbc.getDataSource()));
        if (!(resource instanceof ConnectionHolder holder)
                || holder.getConnectionHandle() == null) {
            throw new IllegalStateException("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        }
        Boolean bound =
                jdbc.execute(
                        (ConnectionCallback<Boolean>)
                                connection -> {
                                    // 종료 억제 프록시가 아닌 실제 연결과 바인딩된 자원을 대조한다.
                                    var target = DataSourceUtils.getTargetConnection(connection);
                                    return DataSourceUtils.isConnectionTransactional(
                                                    target, jdbc.getDataSource())
                                            && target
                                                    == DataSourceUtils.getTargetConnection(
                                                            holder.getConnection())
                                            && !target.getAutoCommit();
                                });
        Boolean validTransaction =
                jdbc.queryForObject(
                        """
                        SELECT current_setting('transaction_isolation')='read committed'
                            AND current_setting('transaction_read_only')='off'
                        """,
                        Boolean.class);
        if (!Boolean.TRUE.equals(bound) || !Boolean.TRUE.equals(validTransaction)) {
            throw new IllegalStateException("SOURCE_REQUIRES_WRITE_READ_COMMITTED");
        }
        return holder;
    }

    /** JOIN은 발견만 하며 어떤 하위 행도 미리 잠그지 않는다. */
    private Hints discover(UUID key) {
        var rows =
                jdbc.query(
                        RELATIONSHIPS,
                        (row, index) ->
                                new Hints(
                                        row.getLong("job_id"),
                                        row.getLong("batch_id"),
                                        row.getLong("snapshot_id"),
                                        row.getLong("runtime_id"),
                                        row.getLong("creator_id"),
                                        row.getLong("batch_runtime_id"),
                                        row.getLong("batch_snapshot_id"),
                                        row.getLong("version_id"),
                                        row.getLong("story_id")),
                        key);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    /** 테이블·컬럼은 내부 고정 상수만 사용하며 한 실제 루트씩 잠근다. */
    private Map<String, Object> locked(String table, String key, long id, String columns) {
        var rows =
                jdbc.queryForList(
                        "SELECT "
                                + columns
                                + " FROM public."
                                + table
                                + " WHERE "
                                + key
                                + "=? FOR UPDATE",
                        id);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    /** 이미 잠근 실제 행을 다시 읽으며 새 루트 잠금을 추가하지 않는다. */
    private Map<String, Object> current(String table, String key, long id, String columns) {
        var rows =
                jdbc.queryForList(
                        "SELECT " + columns + " FROM public." + table + " WHERE " + key + "=?", id);
        if (rows.size() != 1) throw new IllegalStateException("SOURCE_NOT_CURRENT");
        return rows.getFirst();
    }

    /** 불변 사본의 실제 부모와 원문 없는 메타데이터만 읽는다. */
    private Map<String, Object> snapshot(Hints hints) {
        Map<String, Object> snapshot =
                jdbc.queryForMap(
                        "SELECT version_id,edit_rev,format_no FROM public.review_snapshot WHERE"
                                + " id=?",
                        hints.snapshotId());
        if (number(snapshot, "version_id") != hints.versionId()) reject();
        return snapshot;
    }

    /** JDBC 시각 변환은 드라이버의 OffsetDateTime 매핑을 사용한다. */
    private OffsetDateTime createdAt(Hints hints) {
        return jdbc.queryForObject(
                "SELECT created_at FROM public.grade_batch WHERE id=?",
                OffsetDateTime.class,
                hints.batchId());
    }

    /** 원문 없는 고정 메타데이터를 구성하며 저장된 해시의 진위를 주장하지 않는다. */
    private RootEvidence evidence(
            Hints hints,
            UUID jobKey,
            Map<String, Object> runtime,
            Map<String, Object> version,
            Map<String, Object> batch,
            Map<String, Object> job,
            Map<String, Object> snapshot,
            OffsetDateTime created,
            OffsetDateTime now) {
        return new RootEvidence(
                hints.jobId(),
                jobKey,
                hints.batchId(),
                (UUID) batch.get("batch_key"),
                hints.creatorId(),
                hints.storyId(),
                hints.versionId(),
                hints.snapshotId(),
                hints.batchRuntimeId(),
                (String) runtime.get("code"),
                (String) runtime.get("config_hash"),
                number(runtime, "epoch"),
                (String) batch.get("purpose"),
                (String) version.get("status"),
                number(version, "edit_rev"),
                number(snapshot, "edit_rev"),
                number(snapshot, "format_no"),
                (String) version.get("policy_code"),
                (String) batch.get("payload_hash"),
                (String) batch.get("dataset_hash"),
                (String) batch.get("rubric_hash"),
                (String) job.get("input_hash"),
                (String) job.get("sample_code"),
                number(job, "repeat_no"),
                created,
                now);
    }

    /** JDBC 정수 폭 차이만 흡수한다. */
    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** 권한·출처 거절에 SQL 데이터나 원문을 포함하지 않는다. */
    private static void reject() {
        throw new IllegalStateException("SOURCE_NOT_CURRENT");
    }

    private record Hints(
            long jobId,
            long batchId,
            long snapshotId,
            long runtimeId,
            long creatorId,
            long batchRuntimeId,
            long batchSnapshotId,
            long versionId,
            long storyId) {}

    /** 관리된 저장점 롤백은 실제 잠금을 해제할 수 있으므로 모든 기존 잠금 증거를 보수적으로 무효화한다. */
    private static final class LockLifetime implements TransactionSynchronization {
        private boolean valid = true;

        @Override
        public void savepointRollback(Object savepoint) {
            valid = false;
        }

        @Override
        public void afterCompletion(int status) {
            valid = false;
        }
    }

    /** 사실 증거만 보관하며 연결 자원·생성자를 외부에 노출하거나 공개 생성하지 않는다. */
    public static final class LockedSource {
        private final GradeSourceRepository owner;
        private final ConnectionHolder holder;
        private final LockLifetime transaction;
        private final Hints hints;
        private final RootEvidence facts;
        private final String runtimeState;
        private final String batchState;
        private final String jobState;
        private final boolean creatorActive;
        private final boolean creatorCanReview;
        private final boolean credentialEnrolled;
        private final String credentialMfaState;
        private final boolean storyActive;
        private final boolean versionActive;

        private LockedSource(
                GradeSourceRepository owner,
                ConnectionHolder holder,
                LockLifetime transaction,
                Hints hints,
                RootEvidence facts,
                String runtimeState,
                String batchState,
                String jobState,
                boolean creatorActive,
                boolean creatorCanReview,
                boolean credentialEnrolled,
                String credentialMfaState,
                boolean storyActive,
                boolean versionActive) {
            this.owner = owner;
            this.holder = Objects.requireNonNull(holder);
            this.transaction = Objects.requireNonNull(transaction);
            this.hints = hints;
            this.facts = facts;
            this.runtimeState = runtimeState;
            this.batchState = batchState;
            this.jobState = jobState;
            this.creatorActive = creatorActive;
            this.creatorCanReview = creatorCanReview;
            this.credentialEnrolled = credentialEnrolled;
            this.credentialMfaState = credentialMfaState;
            this.storyActive = storyActive;
            this.versionActive = versionActive;
        }

        public long jobId() {
            return facts.jobId();
        }

        public UUID jobKey() {
            return facts.jobKey();
        }

        public long batchId() {
            return facts.batchId();
        }

        public UUID batchKey() {
            return facts.batchKey();
        }

        public long creatorId() {
            return facts.creatorId();
        }

        public long storyId() {
            return facts.storyId();
        }

        public long versionId() {
            return facts.versionId();
        }

        public long snapshotId() {
            return facts.snapshotId();
        }

        public long runtimeId() {
            return facts.runtimeId();
        }

        public String runtimeCode() {
            return facts.runtimeCode();
        }

        public String configHash() {
            return facts.configHash();
        }

        public long runtimeEpoch() {
            return facts.runtimeEpoch();
        }

        public String purpose() {
            return facts.purpose();
        }

        public String versionStatus() {
            return facts.versionStatus();
        }

        public long versionRev() {
            return facts.versionRev();
        }

        public long snapshotRev() {
            return facts.snapshotRev();
        }

        public long snapshotFormat() {
            return facts.snapshotFormat();
        }

        public String policyCode() {
            return facts.policyCode();
        }

        public String payloadHash() {
            return facts.payloadHash();
        }

        public String datasetHash() {
            return facts.datasetHash();
        }

        public String rubricHash() {
            return facts.rubricHash();
        }

        public String inputHash() {
            return facts.inputHash();
        }

        public String sampleCode() {
            return facts.sampleCode();
        }

        public long repeatNo() {
            return facts.repeatNo();
        }

        public OffsetDateTime batchCreatedAt() {
            return facts.batchCreatedAt();
        }

        public String runtimeState() {
            return runtimeState;
        }

        public String batchState() {
            return batchState;
        }

        public String jobState() {
            return jobState;
        }

        public boolean creatorActive() {
            return creatorActive;
        }

        public boolean creatorCanReview() {
            return creatorCanReview;
        }

        public boolean credentialEnrolled() {
            return credentialEnrolled;
        }

        public String credentialMfaState() {
            return credentialMfaState;
        }

        public boolean storyActive() {
            return storyActive;
        }

        public boolean versionActive() {
            return versionActive;
        }

        @Override
        public String toString() {
            return "LockedSource[jobId="
                    + jobId()
                    + ", batchId="
                    + batchId()
                    + ", snapshotId="
                    + snapshotId()
                    + "]";
        }
    }

    /** 저장된 해시는 미검증 선언값이며 검사 시각은 이후 예약의 시각을 대신하지 않는다. */
    public record RootEvidence(
            long jobId,
            UUID jobKey,
            long batchId,
            UUID batchKey,
            long creatorId,
            long storyId,
            long versionId,
            long snapshotId,
            long runtimeId,
            String runtimeCode,
            String configHash,
            long runtimeEpoch,
            String purpose,
            String versionStatus,
            long versionRev,
            long snapshotRev,
            long snapshotFormat,
            String policyCode,
            String payloadHash,
            String datasetHash,
            String rubricHash,
            String inputHash,
            String sampleCode,
            long repeatNo,
            OffsetDateTime batchCreatedAt,
            OffsetDateTime checkedAt) {
        @Override
        public String toString() {
            return "RootEvidence[jobId="
                    + jobId
                    + ", batchId="
                    + batchId
                    + ", snapshotId="
                    + snapshotId
                    + "]";
        }
    }
}
