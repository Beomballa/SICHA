package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reasoning.common.auth.service.AuthException;
import com.reasoning.common.member.LocalMemberAuthIT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 격리 PostgreSQL과 실제 회원 인증·PLAYTEST 정책·고정 REVIEW 사본에 연결한 합성 조사 회귀다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(LocalMemberAuthIT.Installation.class)
public class PlaytestInvestigationIT extends LocalMemberAuthIT {
    @Autowired PlaytestInvestigationService investigation;
    private boolean policyReady;

    private record Pair(
            UUID key,
            String a,
            String b,
            String outsider,
            UUID acceptKey,
            String policyCode,
            String noticeHash) {}

    /** 별도 회원 세션 두 개의 동의를 고정하고 타인 자격을 독립적으로 발급한다. */
    private Pair pair() throws Exception {
        if (!policyReady) {
            playtestPolicy();
            policyReady = true;
        }
        String a =
                account("investigation-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String b =
                account("investigation-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String outsider =
                account("investigation-other-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID key =
                invitation(
                        List.of(
                                invitations.getMemberIdentity(a, UUID.randomUUID()).memberKey(),
                                invitations.getMemberIdentity(b, UUID.randomUUID()).memberKey()));
        var first = invitations.getConsentNotice(a, key, UUID.randomUUID());
        UUID acceptKey = UUID.randomUUID();
        invitations.acceptInvitation(
                a, key, 1, 0, first.policyCode(), first.noticeHash(), acceptKey, UUID.randomUUID());
        var second = invitations.getConsentNotice(b, key, UUID.randomUUID());
        invitations.acceptInvitation(
                b,
                key,
                1,
                1,
                second.policyCode(),
                second.noticeHash(),
                UUID.randomUUID(),
                UUID.randomUUID());
        return new Pair(key, a, b, outsider, acceptKey, first.policyCode(), first.noticeHash());
    }

    private long revision(UUID key) {
        return db.queryForObject("SELECT rev FROM play_test WHERE test_key=?", Long.class, key);
    }

    private int count(UUID key, String table, String action) {
        return db.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE scope_key=? AND action=?",
                Integer.class,
                "test:" + key,
                action);
    }

    /** 양쪽 수락·준비만으로 시작하지 못하며 양쪽 활동 확인 후 한 번만 시작한다. */
    @Test
    void twoSessionsReadyHeartbeatStartAndImmutableReplay() throws Exception {
        Pair pair = pair();
        UUID key = pair.key();
        assertThatThrownBy(
                        () ->
                                investigation.ready(
                                        pair.outsider(),
                                        key,
                                        2,
                                        true,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("NOT_FOUND");

        UUID readyKey = UUID.randomUUID();
        var first = investigation.ready(pair.a(), key, 2, true, readyKey, UUID.randomUUID());
        assertThat(first.replayed()).isFalse();
        assertThat(revision(key)).isEqualTo(3);
        var replay = investigation.ready(pair.a(), key, 2, true, readyKey, UUID.randomUUID());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.original()).containsEntry("rev", "3");
        assertThatThrownBy(
                        () ->
                                investigation.ready(
                                        pair.a(), key, 2, false, readyKey, UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                investigation.ready(
                                        pair.b(), key, 3, true, readyKey, UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThatThrownBy(
                        () ->
                                investigation.start(
                                        pair.a(), key, 3, UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("PARTNER_NOT_READY");

        investigation.ready(pair.b(), key, 3, true, UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                investigation.start(
                                        pair.a(), key, 4, UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("PARTNER_NOT_READY");
        investigation.heartbeat(pair.a(), key, UUID.randomUUID());
        investigation.heartbeat(pair.b(), key, UUID.randomUUID());
        assertThat(revision(key)).isEqualTo(4);
        UUID startKey = UUID.randomUUID();
        var started = investigation.start(pair.b(), key, 4, startKey, UUID.randomUUID());
        assertThat(started.replayed()).isFalse();
        assertThat(started.current()).containsEntry("state", "RUNNING");
        assertThat(investigation.start(pair.b(), key, 4, startKey, UUID.randomUUID()).replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                investigation.start(
                                        pair.a(), key, 5, UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("STATE_CONFLICT");
        assertThat(revision(key)).isEqualTo(5);
        assertThat(count(key, "test_action", "TEST_READY")).isEqualTo(2);
        assertThat(count(key, "test_audit", "TEST_START")).isEqualTo(1);
    }

    /** 서로 다른 키로 동시에 시작해도 한 거래만 역할·시각·영수증·감사를 확정한다. */
    @Test
    void concurrentDistinctKeysStartExactlyOnce() throws Exception {
        Pair pair = pair();
        UUID key = pair.key();
        investigation.ready(pair.a(), key, 2, true, UUID.randomUUID(), UUID.randomUUID());
        investigation.ready(pair.b(), key, 3, true, UUID.randomUUID(), UUID.randomUUID());
        investigation.heartbeat(pair.a(), key, UUID.randomUUID());
        investigation.heartbeat(pair.b(), key, UUID.randomUUID());

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        UUID keyA = UUID.randomUUID();
        UUID keyB = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.function.BiFunction<String, UUID, java.util.concurrent.Callable<String>>
                    attempt =
                            (token, requestKey) ->
                                    () -> {
                                        ready.countDown();
                                        if (!start.await(5, TimeUnit.SECONDS))
                                            throw new AssertionError("start gate timed out");
                                        try {
                                            var result =
                                                    investigation.start(
                                                            token,
                                                            key,
                                                            4,
                                                            requestKey,
                                                            UUID.randomUUID());
                                            assertThat(result.replayed()).isFalse();
                                            return result.current().get("state").toString();
                                        } catch (AuthException denied) {
                                            assertThat(denied.status()).isEqualTo(409);
                                            return denied.code();
                                        }
                                    };
            var a = pool.submit(attempt.apply(pair.a(), keyA));
            var b = pool.submit(attempt.apply(pair.b(), keyB));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String resultA = a.get(15, TimeUnit.SECONDS);
            String resultB = b.get(15, TimeUnit.SECONDS);
            assertThat(List.of(resultA, resultB))
                    .containsExactlyInAnyOrder("RUNNING", "STATE_CONFLICT");
            String winner = "RUNNING".equals(resultA) ? pair.a() : pair.b();
            UUID winningKey = "RUNNING".equals(resultA) ? keyA : keyB;
            var assigned =
                    db.queryForList(
                            "SELECT slot,role_code FROM test_member m JOIN play_test t"
                                    + " ON t.id=m.test_id WHERE t.test_key=? ORDER BY slot",
                            key);
            assertThat(
                            investigation
                                    .start(winner, key, 4, winningKey, UUID.randomUUID())
                                    .replayed())
                    .isTrue();
            assertThat(
                            db.queryForList(
                                    "SELECT slot,role_code FROM test_member m JOIN play_test t"
                                            + " ON t.id=m.test_id WHERE t.test_key=? ORDER BY slot",
                                    key))
                    .isEqualTo(assigned);
        }

        var roles =
                db.queryForList(
                        "SELECT slot,role_code FROM test_member m JOIN play_test t"
                                + " ON t.id=m.test_id WHERE t.test_key=? ORDER BY slot",
                        key);
        assertThat(roles).hasSize(2);
        assertThat(roles.stream().map(row -> row.get("role_code")).toList())
                .containsExactlyInAnyOrder("R1", "R2");
        assertThat(
                        db.queryForMap(
                                "SELECT state,rev,started_at,deadline_at FROM play_test"
                                        + " WHERE test_key=?",
                                key))
                .containsEntry("state", "RUNNING")
                .containsEntry("rev", 5L)
                .containsKeys("started_at", "deadline_at");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_action WHERE scope_key=?"
                                        + " AND action='TEST_START'",
                                Integer.class,
                                "test:" + key))
                .isEqualTo(1);
        assertThat(count(key, "test_audit", "TEST_START")).isEqualTo(1);
    }

    /** 노출은 본인 역할만을 투영하고 최초 제공 시점에만 영구 기록한다. */
    @Test
    void roleFilteredMaterialsAndOutsiderCannotCreateExposure() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        assertThatThrownBy(() -> investigation.materials(pair.outsider(), key, UUID.randomUUID()))
                .hasMessageContaining("NOT_FOUND");
        assertThat(exposures(key)).isZero();
        var frozen =
                JSON.readTree(
                        db.queryForObject(
                                "SELECT s.payload::text FROM review_snapshot s JOIN play_test t"
                                        + " ON t.snapshot_id=s.id WHERE t.test_key=?",
                                String.class,
                                key));
        var expectedNotices = new java.util.ArrayList<String>();
        for (var rubric : frozen.path("resources").path("rubrics")) {
            if (rubric.path("requiredYn").asBoolean()) {
                expectedNotices.add(
                        rubric.path("code").asText()
                                + ":"
                                + rubric.path("ruleData").path("requiredNotice").asText());
            }
        }
        for (String token : List.of(pair.a(), pair.b())) {
            var materials =
                    JSON.valueToTree(investigation.materials(token, key, UUID.randomUUID()));
            assertThat(materials.has("basic")).isTrue();
            assertThat(materials.has("role")).isTrue();
            assertThat(materials.has("persons")).isTrue();
            assertThat(materials.has("clues")).isTrue();
            assertThat(materials.has("requiredNotices")).isTrue();
            var notices = new java.util.ArrayList<String>();
            for (var notice : materials.path("requiredNotices")) {
                notices.add(
                        notice.path("rubricCode").asText() + ":" + notice.path("text").asText());
            }
            assertThat(notices).containsExactlyElementsOf(expectedNotices);
            assertThat(materials.path("openedHints")).isEmpty();
            assertThat(materials.path("clues")).hasSize(1);
            String role = materials.path("role").path("code").asText();
            assertThat(role).isIn("R1", "R2");
            assertThat(materials.path("clues").get(0).path("code").asText())
                    .isEqualTo(role.equals("R1") ? "C1" : "C2");
            String text = materials.toString();
            assertThat(text)
                    .doesNotContain(
                            "secretText",
                            "sourceText",
                            "facts",
                            "rubrics",
                            "gradeSamples",
                            "ruleData",
                            "출처",
                            "비밀");
        }
        assertThat(exposures(key)).isEqualTo(2);
        investigation.materials(pair.a(), key, UUID.randomUUID());
        assertThat(exposures(key)).isEqualTo(2);
        assertThat(count(key, "test_audit", "TEST_MATERIALS")).isGreaterThanOrEqualTo(2);
    }

    /** 단계 건너뛰기·같은 단계 재열람은 본인 한도를 재소모하지 않고 상대 한도와 분리한다. */
    @Test
    void hintLevelReplayLimitAndIndependentMemberBudget() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        UUID firstKey = UUID.randomUUID();
        var opened = investigation.openHint(pair.a(), key, 2, 5, firstKey, UUID.randomUUID());
        assertThat(opened.replayed()).isFalse();
        assertThat(opened.original().toString()).doesNotContain("합성 두 번째 힌트");
        assertThat(
                        investigation
                                .openHint(pair.a(), key, 2, 5, firstKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                investigation.openHint(
                                        pair.a(), key, 1, 5, firstKey, UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        assertThat(
                        investigation
                                .openHint(pair.a(), key, 2, 6, UUID.randomUUID(), UUID.randomUUID())
                                .changed())
                .isFalse();
        investigation.openHint(pair.a(), key, 1, 6, UUID.randomUUID(), UUID.randomUUID());
        assertThat(hints(key, pair.a())).isEqualTo(2);
        assertThat(hints(key, pair.b())).isZero();
        assertThatThrownBy(
                        () ->
                                investigation.openHint(
                                        pair.a(), key, 3, 7, UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("HINT_LIMIT_REACHED");
        assertThat(
                        JSON.valueToTree(investigation.materials(pair.b(), key, UUID.randomUUID()))
                                .path("openedHints"))
                .isEmpty();
        investigation.openHint(pair.b(), key, 1, 7, UUID.randomUUID(), UUID.randomUUID());
        assertThat(hints(key, pair.b())).isEqualTo(1);
        assertThat(
                        JSON.valueToTree(investigation.materials(pair.a(), key, UUID.randomUUID()))
                                .path("openedHints"))
                .hasSize(2);
        assertThat(count(key, "test_action", "HINT_OPEN")).isEqualTo(4);
    }

    /** 선행 단계 없이 세 번째 힌트를 처음 열어도 본인 한도 한 번만 소비한다. */
    @Test
    void thirdHintCanBeFirstWithoutOpeningEarlierLevels() throws Exception {
        Pair pair = running();
        UUID requestKey = UUID.randomUUID();
        var opened =
                investigation.openHint(pair.a(), pair.key(), 3, 5, requestKey, UUID.randomUUID());
        assertThat(opened.changed()).isTrue();
        assertThat(opened.original().toString()).doesNotContain("합성 세 번째 힌트");
        assertThat(hints(pair.key(), pair.a())).isEqualTo(1);
        var materials = investigation.materials(pair.a(), pair.key(), UUID.randomUUID());
        assertThat(materials.path("openedHints")).hasSize(1);
        assertThat(materials.path("openedHints").get(0).path("level").asInt()).isEqualTo(3);
        assertThat(
                        investigation
                                .openHint(pair.a(), pair.key(), 3, 5, requestKey, UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThat(hints(pair.key(), pair.a())).isEqualTo(1);
    }

    /** 대기실 마감과 PLAYTEST 정책 중단·REVIEW 철회는 준비와 원문 제공을 거절한다. */
    @Test
    void expiredLobbyAndWithdrawnSourceOrPolicyDenyAccess() throws Exception {
        Pair expired = pair();
        db.update(
                "UPDATE play_test SET ready_until=created_at+interval '1 microsecond'"
                        + " WHERE test_key=?",
                expired.key());
        assertThatThrownBy(
                        () ->
                                investigation.ready(
                                        expired.a(),
                                        expired.key(),
                                        2,
                                        true,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("INVITATION_EXPIRED");
        assertExpiredOnce(expired.key());

        Pair withdrawn = running();
        db.update(
                "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL WHERE id="
                        + "(SELECT version_id FROM play_test WHERE test_key=?)",
                withdrawn.key());
        assertThatThrownBy(
                        () ->
                                investigation.materials(
                                        withdrawn.a(), withdrawn.key(), UUID.randomUUID()))
                .hasMessageContaining("INVITATION_INVALIDATED");
        assertThat(exposures(withdrawn.key())).isZero();

        Pair suspended = running();
        db.update(
                "UPDATE privacy_policy SET state='SUSPENDED' WHERE scope='PLAYTEST'"
                        + " AND state='ACTIVE'");
        assertThatThrownBy(
                        () ->
                                investigation.materials(
                                        suspended.a(), suspended.key(), UUID.randomUUID()))
                .hasMessageContaining("PLAYTEST_COLLECTION_NOT_READY");
        assertThat(exposures(suspended.key())).isZero();
    }

    /** 필수 자료 제공 감사 실패가 노출 기록과 응답을 함께 되돌린다. */
    @Test
    void failedMaterialsAuditRollsBackExposure() throws Exception {
        Pair pair = running();
        db.execute(
                "CREATE FUNCTION public.h5_reject_materials_audit() RETURNS trigger LANGUAGE"
                        + " plpgsql AS $$ BEGIN IF NEW.action='TEST_MATERIALS' THEN RAISE EXCEPTION"
                        + " 'synthetic materials audit failure'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER h5_reject_materials_audit BEFORE INSERT ON public.test_audit"
                        + " FOR EACH ROW EXECUTE FUNCTION public.h5_reject_materials_audit()");
        try {
            assertThatThrownBy(
                            () -> investigation.materials(pair.a(), pair.key(), UUID.randomUUID()))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            db.execute("DROP TRIGGER h5_reject_materials_audit ON public.test_audit");
            db.execute("DROP FUNCTION public.h5_reject_materials_audit()");
        }
        assertThat(exposures(pair.key())).isZero();
        investigation.materials(pair.a(), pair.key(), UUID.randomUUID());
        assertThat(exposures(pair.key())).isEqualTo(1);
    }

    /** 시작 뒤의 서버 마감은 새 자료 노출 없이 시간 초과로 영속 종료된다. */
    @Test
    void playDeadlineEndsSessionBeforeMaterialExposure() throws Exception {
        Pair pair = running();
        db.update(
                "UPDATE play_test SET deadline_at=started_at+interval '1 microsecond'"
                        + " WHERE test_key=?",
                pair.key());
        assertThatThrownBy(() -> investigation.materials(pair.a(), pair.key(), UUID.randomUUID()))
                .hasMessageContaining("TEST_EXPIRED");
        assertThat(
                        db.queryForMap(
                                "SELECT state,outcome,final_score FROM play_test WHERE test_key=?",
                                pair.key()))
                .containsEntry("state", "ENDED")
                .containsEntry("outcome", "TIME_LIMIT")
                .containsEntry("final_score", 0);
        assertThat(exposures(pair.key())).isZero();
    }

    /** 기한 후 heartbeat는 접속 시각을 갱신하지 않고 시간초과 종료만 확정한다. */
    @Test
    void lateHeartbeatCommitsDeadlineWithoutRefreshingPresence() throws Exception {
        Pair pair = running();
        UUID member = invitations.getMemberIdentity(pair.a(), UUID.randomUUID()).memberKey();
        var before =
                db.queryForObject(
                        "SELECT last_seen_at FROM test_member m JOIN member_account a ON"
                                + " a.id=m.member_id JOIN play_test t ON t.id=m.test_id WHERE"
                                + " t.test_key=? AND a.member_key=?",
                        java.sql.Timestamp.class,
                        pair.key(),
                        member);
        assertThat(before).isNotNull();
        db.update(
                "UPDATE play_test SET deadline_at=started_at+interval '1 microsecond'"
                        + " WHERE test_key=?",
                pair.key());

        assertThatThrownBy(() -> investigation.heartbeat(pair.a(), pair.key(), UUID.randomUUID()))
                .hasMessageContaining("TEST_EXPIRED");
        assertThat(
                        db.queryForMap(
                                "SELECT state,outcome,final_score FROM play_test WHERE test_key=?",
                                pair.key()))
                .containsEntry("state", "ENDED")
                .containsEntry("outcome", "TIME_LIMIT")
                .containsEntry("final_score", 0);
        assertThat(
                        db.queryForObject(
                                "SELECT last_seen_at FROM test_member m JOIN member_account a ON"
                                    + " a.id=m.member_id JOIN play_test t ON t.id=m.test_id WHERE"
                                    + " t.test_key=? AND a.member_key=?",
                                java.sql.Timestamp.class,
                                pair.key(),
                                member))
                .isEqualTo(before);
        assertThat(count(pair.key(), "test_audit", "TEST_DEADLINE")).isEqualTo(1);
        assertThat(exposures(pair.key())).isZero();
    }

    /** 쌍의 다른 회원이 정지되면 유효한 본인 토큰도 역할 원문을 노출하지 못한다. */
    @Test
    void suspendedPartnerDeniesMaterialsBeforeExposure() throws Exception {
        Pair pair = running();
        UUID partner = invitations.getMemberIdentity(pair.b(), UUID.randomUUID()).memberKey();
        db.update("UPDATE member_account SET state='BLOCKED' WHERE member_key=?", partner);
        assertThatThrownBy(() -> investigation.materials(pair.a(), pair.key(), UUID.randomUUID()))
                .hasMessageContaining("PLAYTEST_UNAVAILABLE");
        assertThat(exposures(pair.key())).isZero();
        assertThat(count(pair.key(), "test_audit", "TEST_MATERIALS")).isZero();
    }

    /** 진행 중 관리자 회수는 쌍 전체를 취소하고 이후 역할 원문 노출을 차단한다. */
    @Test
    void runningAdminRevokeCancelsBothParticipants() throws Exception {
        Pair pair = running();
        var revoked =
                invitations.revokeInvitation(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        pair.key(),
                        5,
                        "ACCESS_REVOKED",
                        "synthetic-revoke",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        assertThat(revoked.current()).containsEntry("state", "CANCELLED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_member m JOIN play_test t ON"
                                        + " t.id=m.test_id WHERE t.test_key=? AND"
                                        + " m.invite_state='REVOKED'",
                                Integer.class,
                                pair.key()))
                .isEqualTo(2);
        for (String token : List.of(pair.a(), pair.b())) {
            assertThatThrownBy(() -> investigation.materials(token, pair.key(), UUID.randomUUID()))
                    .hasMessageContaining("INVITATION_INVALIDATED");
        }
        assertThat(exposures(pair.key())).isZero();
    }

    /** 기존 시작·요청 키가 있어도 회원 로그아웃 뒤에는 노출과 재전송을 허용하지 않는다. */
    @Test
    void revokedMemberSessionCannotReadOrReplay() throws Exception {
        Pair pair = running();
        UUID key = pair.key();
        UUID hintKey = UUID.randomUUID();
        investigation.openHint(pair.a(), key, 1, 5, hintKey, UUID.randomUUID());
        service.logout(pair.a(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> investigation.materials(pair.a(), key, UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
        assertThatThrownBy(
                        () ->
                                investigation.openHint(
                                        pair.a(), key, 1, 5, hintKey, UUID.randomUUID()))
                .hasMessageContaining("MEMBER_AUTH_REQUIRED");
        assertThat(exposures(key)).isEqualTo(1);
    }

    /** 실제 dispatcher가 엄격한 revision/body를 검사하고 공개 DTO·204 응답을 전달한다. */
    @Test
    void pairedHttpRoutesRejectMalformedCommandsAndProjectMaterials() throws Exception {
        Pair pair = pair();
        UUID key = pair.key();
        String base = "/api/playtests/" + key;
        mvc.perform(
                        post(base + "/ready")
                                .secure(true)
                                .header("Authorization", "Bearer " + pair.a())
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                Map.of(
                                                        "expectedRev",
                                                        2,
                                                        "ready",
                                                        true,
                                                        "requestKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isBadRequest());
        assertThat(revision(key)).isEqualTo(2);
        for (String token : List.of(pair.a(), pair.b())) {
            var ready =
                    mvc.perform(
                                    post(base + "/ready")
                                            .secure(true)
                                            .header("Authorization", "Bearer " + token)
                                            .contentType("application/json")
                                            .content(
                                                    JSON.writeValueAsBytes(
                                                            Map.of(
                                                                    "expectedRev",
                                                                    Long.toString(revision(key)),
                                                                    "ready",
                                                                    true,
                                                                    "requestKey",
                                                                    UUID.randomUUID().toString()))))
                            .andExpect(status().isOk())
                            .andReturn();
            assertThat(ready.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            mvc.perform(
                            post(base + "/heartbeat")
                                    .secure(true)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(
                        post(base + "/start")
                                .secure(true)
                                .header("Authorization", "Bearer " + pair.a())
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                Map.of(
                                                        "expectedRev",
                                                        "4",
                                                        "requestKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
        mvc.perform(
                        get(base + "/materials")
                                .secure(true)
                                .header("Authorization", "Bearer " + pair.outsider()))
                .andExpect(status().isNotFound());
        var material =
                mvc.perform(
                                get(base + "/materials")
                                        .secure(true)
                                        .header("Authorization", "Bearer " + pair.a()))
                        .andExpect(status().isOk())
                        .andReturn();
        var body = JSON.readTree(material.getResponse().getContentAsByteArray());
        assertThat(body.path("role").path("code").asText()).isIn("R1", "R2");
        assertThat(body.path("clues")).hasSize(1);
        assertThat(body.has("content")).isFalse();
        assertThat(material.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(exposures(key)).isEqualTo(1);
        mvc.perform(
                        post(base + "/hints/1/open")
                                .secure(true)
                                .header("Authorization", "Bearer " + pair.a())
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                Map.of(
                                                        "expectedRev",
                                                        "5",
                                                        "requestKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
        assertThat(hints(key, pair.a())).isEqualTo(1);
    }

    /** 실제 수락한 쌍의 잠긴 대기 마감은 오류 응답 뒤에도 한 번만 영속된다. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "detail",
                "consent",
                "accept",
                "revoke",
                "ready",
                "start",
                "heartbeat",
                "materials",
                "hint"
            })
    void waitingExpiryCommitsAcrossExistingEndpoints(String endpoint) throws Exception {
        Pair pair = pair();
        expireLobby(pair.key());
        Map<String, Object> expired = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(
                            () -> {
                                switch (endpoint) {
                                    case "detail" ->
                                            invitations.getMemberInvitationDetail(
                                                    pair.a(), pair.key(), UUID.randomUUID());
                                    case "consent" ->
                                            invitations.getConsentNotice(
                                                    pair.a(), pair.key(), UUID.randomUUID());
                                    case "accept" ->
                                            invitations.acceptInvitation(
                                                    pair.a(),
                                                    pair.key(),
                                                    1,
                                                    0,
                                                    pair.policyCode(),
                                                    pair.noticeHash(),
                                                    pair.acceptKey(),
                                                    UUID.randomUUID());
                                    case "revoke" ->
                                            invitations.revokeInvitation(
                                                    invitationSid,
                                                    invitationActor,
                                                    invitationStoryCode,
                                                    1,
                                                    pair.key(),
                                                    2,
                                                    "ACCESS_REVOKED",
                                                    "synthetic-expiry",
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID());
                                    case "ready" ->
                                            investigation.ready(
                                                    pair.a(),
                                                    pair.key(),
                                                    2,
                                                    true,
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID());
                                    case "start" ->
                                            investigation.start(
                                                    pair.a(),
                                                    pair.key(),
                                                    2,
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID());
                                    case "heartbeat" ->
                                            investigation.heartbeat(
                                                    pair.a(), pair.key(), UUID.randomUUID());
                                    case "materials" ->
                                            investigation.materials(
                                                    pair.a(), pair.key(), UUID.randomUUID());
                                    case "hint" ->
                                            investigation.openHint(
                                                    pair.a(),
                                                    pair.key(),
                                                    1,
                                                    2,
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID());
                                    default -> throw new AssertionError(endpoint);
                                }
                            })
                    .isInstanceOfSatisfying(
                            AuthException.class,
                            denied -> assertThat(denied.status()).isEqualTo(410));
            assertExpiredOnce(pair.key());
            if (expired == null) expired = lifecycle(pair.key());
            else assertThat(lifecycle(pair.key())).isEqualTo(expired);
        }
        assertThat(count(pair.key(), "test_action", "TEST_READY")).isZero();
        assertThat(count(pair.key(), "test_action", "TEST_START")).isZero();
        assertThat(count(pair.key(), "test_action", "INVITATION_REVOKE")).isZero();
        assertThat(exposures(pair.key())).isZero();
    }

    /** HTTP 거절은 마감 전이를 롤백하지 않으며 두 번째 GET은 시각을 덮어쓰지 않는다. */
    @Test
    void waitingExpiryHttpDenialPreservesRevisionAuditAndEndTime() throws Exception {
        Pair pair = pair();
        expireLobby(pair.key());
        String base = "/api/playtests/" + pair.key();
        mvc.perform(
                        post(base + "/ready")
                                .secure(true)
                                .header("Authorization", "Bearer " + pair.a())
                                .contentType("application/json")
                                .content(
                                        JSON.writeValueAsBytes(
                                                Map.of(
                                                        "expectedRev",
                                                        "2",
                                                        "ready",
                                                        true,
                                                        "requestKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isGone());
        assertExpiredOnce(pair.key());
        var ended = lifecycle(pair.key());
        mvc.perform(get(base).secure(true).header("Authorization", "Bearer " + pair.b()))
                .andExpect(status().isGone());
        assertThat(lifecycle(pair.key())).isEqualTo(ended);
        assertExpiredOnce(pair.key());
    }

    /** 수락 원본은 RUNNING/ENDED에서도 그대로 복구하되 현재 접근 철회는 우회하지 않는다. */
    @Test
    void acceptedOriginalReplaysAfterStartButNotSourceOrParticipantRevocation() throws Exception {
        Pair pair = running();
        var replay = replayAccept(pair);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.original()).containsEntry("rev", "1").containsEntry("state", "WAITING");
        assertThat(replay.current()).containsEntry("rev", "5").containsEntry("state", "RUNNING");
        assertThat(count(pair.key(), "test_action", "INVITATION_ACCEPT")).isEqualTo(2);
        assertThat(count(pair.key(), "test_audit", "INVITATION_ACCEPT")).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                invitations.acceptInvitation(
                                        pair.a(),
                                        pair.key(),
                                        1,
                                        1,
                                        pair.policyCode(),
                                        pair.noticeHash(),
                                        pair.acceptKey(),
                                        UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");

        expirePlay(pair.key());
        invitations.getMemberInvitationDetail(pair.a(), pair.key(), UUID.randomUUID());
        var endedReplay = replayAccept(pair);
        assertThat(endedReplay.original()).isEqualTo(replay.original());
        assertThat(endedReplay.current()).containsEntry("state", "ENDED").containsEntry("rev", "6");
        invitations.revokeInvitation(
                invitationSid,
                invitationActor,
                invitationStoryCode,
                1,
                pair.key(),
                6,
                "ACCESS_REVOKED",
                "synthetic-revoke",
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThatThrownBy(() -> replayAccept(pair)).hasMessageContaining("INVITATION_INVALIDATED");

        Pair source = running();
        db.update(
                "UPDATE story_version SET status='DRAFT',current_snapshot_id=NULL WHERE id="
                        + "(SELECT version_id FROM play_test WHERE test_key=?)",
                source.key());
        assertThatThrownBy(() -> replayAccept(source))
                .hasMessageContaining("INVITATION_INVALIDATED");
        assertThat(revision(source.key())).isEqualTo(5);
    }

    /** 자료 요청 없이 GET 또는 시작 원본 재전송만으로 마감 종료가 한 번 영속된다. */
    @Test
    void deadlineOnlyStateReadAndOriginalStartReplayReturnDurableCurrentEnded() throws Exception {
        for (boolean replayFirst : List.of(false, true)) {
            Pair pair = pair();
            investigation.ready(
                    pair.a(), pair.key(), 2, true, UUID.randomUUID(), UUID.randomUUID());
            investigation.ready(
                    pair.b(), pair.key(), 3, true, UUID.randomUUID(), UUID.randomUUID());
            investigation.heartbeat(pair.a(), pair.key(), UUID.randomUUID());
            investigation.heartbeat(pair.b(), pair.key(), UUID.randomUUID());
            UUID requestKey = UUID.randomUUID();
            var started =
                    investigation.start(pair.a(), pair.key(), 4, requestKey, UUID.randomUUID());
            expirePlay(pair.key());
            if (!replayFirst) {
                var response =
                        mvc.perform(
                                        get("/api/playtests/" + pair.key())
                                                .secure(true)
                                                .header("Authorization", "Bearer " + pair.a()))
                                .andExpect(status().isOk())
                                .andReturn();
                var body = JSON.readTree(response.getResponse().getContentAsByteArray());
                assertThat(body.path("state").asText()).isEqualTo("ENDED");
                assertThat(body.path("outcome").asText()).isEqualTo("TIME_LIMIT");
                assertThat(body.path("rev").asText()).isEqualTo("6");
            }
            var replay =
                    investigation.start(pair.a(), pair.key(), 4, requestKey, UUID.randomUUID());
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.original()).isEqualTo(started.original());
            assertThat(replay.original())
                    .containsEntry("state", "RUNNING")
                    .containsEntry("rev", "5");
            assertThat(replay.current())
                    .containsEntry("state", "ENDED")
                    .containsEntry("outcome", "TIME_LIMIT")
                    .containsEntry("rev", "6");
            var ended = lifecycle(pair.key());
            assertThat(ended)
                    .containsEntry("state", "ENDED")
                    .containsEntry("outcome", "TIME_LIMIT")
                    .containsEntry("final_score", 0)
                    .containsEntry("rev", 6L);
            assertThat(ended.get("ended_at")).isNotNull();
            assertThat(ended.get("result_until")).isNotNull();
            assertThat(
                            db.queryForObject(
                                    "SELECT ended_at>=deadline_at AND"
                                            + " result_until=ended_at+interval '24 hours' FROM"
                                            + " play_test WHERE test_key=?",
                                    Boolean.class,
                                    pair.key()))
                    .isTrue();
            for (int i = 0; i < 2; i++) {
                var state =
                        invitations.getMemberInvitationDetail(
                                pair.b(), pair.key(), UUID.randomUUID());
                assertThat(state.state()).isEqualTo("ENDED");
                assertThat(state.rev()).isEqualTo("6");
                assertThat(
                                investigation
                                        .start(
                                                pair.a(),
                                                pair.key(),
                                                4,
                                                requestKey,
                                                UUID.randomUUID())
                                        .original())
                        .isEqualTo(started.original());
                assertThat(lifecycle(pair.key())).isEqualTo(ended);
            }
            assertThat(count(pair.key(), "test_audit", "TEST_DEADLINE")).isEqualTo(1);
            assertThat(count(pair.key(), "test_action", "TEST_START")).isEqualTo(1);
            assertThat(exposures(pair.key())).isZero();
        }
    }

    /** 종료 회수의 동일 새 키 의도는 변경 없이 영수증만 남기며 다른 이유/참조는 거절한다. */
    @Test
    void endedWithdrawalNewKeyVerifiedNoopPreservesHistoricalResultAndTimes() throws Exception {
        Pair pair = running();
        expirePlay(pair.key());
        invitations.getMemberInvitationDetail(pair.a(), pair.key(), UUID.randomUUID());
        var ended = lifecycle(pair.key());
        var revoked =
                invitations.revokeInvitation(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        pair.key(),
                        6,
                        "ACCESS_REVOKED",
                        "synthetic-ended",
                        UUID.randomUUID(),
                        UUID.randomUUID());
        assertThat(revoked.changed()).isTrue();
        assertThat(lifecycle(pair.key())).isEqualTo(ended);
        var members =
                db.queryForList(
                        "SELECT m.slot,m.invite_state,m.revoked_at,m.updated_at FROM test_member m"
                                + " JOIN play_test t ON t.id=m.test_id WHERE t.test_key=? ORDER BY"
                                + " m.slot",
                        pair.key());
        for (var member : members) {
            assertThat(member).containsEntry("invite_state", "REVOKED");
            assertThat(member.get("revoked_at")).isNotNull();
        }
        UUID newKey = UUID.randomUUID();
        var unchanged =
                invitations.revokeInvitation(
                        invitationSid,
                        invitationActor,
                        invitationStoryCode,
                        1,
                        pair.key(),
                        6,
                        "ACCESS_REVOKED",
                        "synthetic-ended",
                        newKey,
                        UUID.randomUUID());
        assertThat(unchanged.changed()).isFalse();
        assertThat(unchanged.replayed()).isFalse();
        assertThat(unchanged.current()).containsEntry("state", "ENDED").containsEntry("rev", "6");
        assertThat(
                        invitations
                                .revokeInvitation(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        pair.key(),
                                        6,
                                        "ACCESS_REVOKED",
                                        "synthetic-ended",
                                        newKey,
                                        UUID.randomUUID())
                                .replayed())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                invitations.revokeInvitation(
                                        invitationSid,
                                        invitationActor,
                                        invitationStoryCode,
                                        1,
                                        pair.key(),
                                        6,
                                        "CONTENT_REVIEW",
                                        "synthetic-ended",
                                        newKey,
                                        UUID.randomUUID()))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        for (String[] intent :
                List.of(
                        new String[] {"CONTENT_REVIEW", "synthetic-ended"},
                        new String[] {"ACCESS_REVOKED", "different-ended"})) {
            assertThatThrownBy(
                            () ->
                                    invitations.revokeInvitation(
                                            invitationSid,
                                            invitationActor,
                                            invitationStoryCode,
                                            1,
                                            pair.key(),
                                            6,
                                            intent[0],
                                            intent[1],
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .hasMessageContaining("STATE_CONFLICT");
        }
        assertThat(lifecycle(pair.key())).isEqualTo(ended);
        assertThat(
                        db.queryForList(
                                "SELECT m.slot,m.invite_state,m.revoked_at,m.updated_at FROM"
                                        + " test_member m JOIN play_test t ON t.id=m.test_id WHERE"
                                        + " t.test_key=? ORDER BY m.slot",
                                pair.key()))
                .isEqualTo(members);
        assertThat(count(pair.key(), "test_action", "INVITATION_REVOKE")).isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_audit WHERE scope_key=? AND"
                                    + " action='INVITATION_REVOKE' AND business_result='REVOKED'",
                                Integer.class,
                                "test:" + pair.key()))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM test_audit WHERE scope_key=? AND"
                                    + " action='INVITATION_REVOKE' AND business_result='UNCHANGED'",
                                Integer.class,
                                "test:" + pair.key()))
                .isEqualTo(1);
    }

    /** 필수 만료 감사 실패는 상태/시각/rev도 롤백하고 이후 정상 요청은 전이를 확정한다. */
    @Test
    void waitingExpiryAuditFailureRollsBackTransition() throws Exception {
        Pair pair = pair();
        expireLobby(pair.key());
        var before = lifecycle(pair.key());
        db.execute(
                "CREATE FUNCTION public.h5_reject_expiry_audit() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN IF NEW.action='INVITATION_EXPIRE' THEN RAISE EXCEPTION"
                        + " 'synthetic expiry audit failure'; END IF; RETURN NEW; END $$");
        db.execute(
                "CREATE TRIGGER h5_reject_expiry_audit BEFORE INSERT ON public.test_audit"
                        + " FOR EACH ROW EXECUTE FUNCTION public.h5_reject_expiry_audit()");
        try {
            assertThatThrownBy(
                            () ->
                                    investigation.ready(
                                            pair.a(),
                                            pair.key(),
                                            2,
                                            true,
                                            UUID.randomUUID(),
                                            UUID.randomUUID()))
                    .isInstanceOfSatisfying(
                            AuthException.class,
                            denied -> assertThat(denied.status()).isEqualTo(503));
        } finally {
            db.execute("DROP TRIGGER h5_reject_expiry_audit ON public.test_audit");
            db.execute("DROP FUNCTION public.h5_reject_expiry_audit()");
        }
        assertThat(lifecycle(pair.key())).isEqualTo(before);
        assertThat(count(pair.key(), "test_audit", "INVITATION_EXPIRE")).isZero();
        assertThatThrownBy(
                        () ->
                                investigation.ready(
                                        pair.a(),
                                        pair.key(),
                                        2,
                                        true,
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("INVITATION_EXPIRED");
        assertExpiredOnce(pair.key());
    }

    private PlaytestInvitationService.ActionResult replayAccept(Pair pair) {
        return invitations.acceptInvitation(
                pair.a(),
                pair.key(),
                1,
                0,
                pair.policyCode(),
                pair.noticeHash(),
                pair.acceptKey(),
                UUID.randomUUID());
    }

    // Isolated timestamp fixtures shorten only mutable clocks, never pinned provenance or DB time.
    private void expireLobby(UUID key) {
        db.update(
                "UPDATE play_test SET ready_until=created_at+interval '1 microsecond'"
                        + " WHERE test_key=?",
                key);
    }

    private void expirePlay(UUID key) {
        db.update(
                "UPDATE play_test SET deadline_at=started_at+interval '1 microsecond'"
                        + " WHERE test_key=?",
                key);
    }

    private Map<String, Object> lifecycle(UUID key) {
        return db.queryForMap(
                "SELECT state,rev,outcome,final_score,started_at,deadline_at,"
                        + "ended_at,result_until,updated_at FROM play_test WHERE test_key=?",
                key);
    }

    private void assertExpiredOnce(UUID key) {
        assertThat(lifecycle(key))
                .containsEntry("state", "EXPIRED")
                .containsEntry("rev", 3L)
                .containsEntry("outcome", null)
                .containsEntry("final_score", null);
        assertThat(lifecycle(key).get("ended_at")).isNotNull();
        assertThat(count(key, "test_audit", "INVITATION_EXPIRE")).isEqualTo(1);
    }

    private Pair running() throws Exception {
        Pair pair = pair();
        investigation.ready(pair.a(), pair.key(), 2, true, UUID.randomUUID(), UUID.randomUUID());
        investigation.ready(pair.b(), pair.key(), 3, true, UUID.randomUUID(), UUID.randomUUID());
        investigation.heartbeat(pair.a(), pair.key(), UUID.randomUUID());
        investigation.heartbeat(pair.b(), pair.key(), UUID.randomUUID());
        investigation.start(pair.a(), pair.key(), 4, UUID.randomUUID(), UUID.randomUUID());
        return pair;
    }

    private int exposures(UUID key) {
        return db.queryForObject(
                "SELECT count(*) FROM test_exposure e"
                        + " JOIN story_version v ON v.story_id=e.story_id"
                        + " JOIN play_test t ON t.version_id=v.id WHERE t.test_key=?",
                Integer.class,
                key);
    }

    private int hints(UUID key, String token) {
        UUID member = invitations.getMemberIdentity(token, UUID.randomUUID()).memberKey();
        return db.queryForObject(
                "SELECT count(*) FROM test_hint h JOIN play_test t ON"
                        + " t.id=h.test_id JOIN member_account m ON m.id=h.member_id"
                        + " WHERE t.test_key=? AND m.member_key=?",
                Integer.class,
                key,
                member);
    }
}
