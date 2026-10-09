package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.member.LocalMemberAuthIT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** V22의 실제 설치·물리 출처·불변 이력을 실제 PG 부모와 함께 검사한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(LocalMemberAuthIT.Installation.class)
public class PlaytestReportSchemaIT extends LocalMemberAuthIT {
    @Autowired PlaytestInvestigationService investigation;
    @Autowired PlaytestReportService reports;

    /** 임베디드 원본 V17~V22를 한 번씩 적용하고 이전 물리 출처를 없애지 않는다. */
    @Test
    void embeddedHistoryRetainsEarlierMigrationsAndRealSourceForeignKeys() {
        var versions =
                db.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success AND"
                                + " version::integer BETWEEN 17 AND 22 ORDER BY version::integer",
                        String.class);
        assertThat(versions).containsExactly("17", "18", "19", "20", "21", "22");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success"
                                        + " AND version='22'",
                                Integer.class))
                .isEqualTo(1);
        Map<String, String> fks =
                db.query(
                        "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid"
                            + " IN ('public.test_report'::regclass,'public.grade_job'::regclass)"
                            + " AND contype='f'",
                        rs -> {
                            var result = new java.util.HashMap<String, String>();
                            while (rs.next()) result.put(rs.getString(1), rs.getString(2));
                            return result;
                        });
        assertThat(fks.get("fk_report_test"))
                .contains(
                        "FOREIGN KEY (snapshot_id, runtime_id, test_id)",
                        "play_test(snapshot_id, runtime_id, id)");
        assertThat(fks.get("fk_report_proposer"))
                .contains("FOREIGN KEY (test_id, proposer_id)", "test_member(test_id, member_id)");
        assertThat(fks.get("fk_report_acceptor"))
                .contains("FOREIGN KEY (test_id, accepted_by)", "test_member(test_id, member_id)");
        assertThat(fks.get("fk_gj_report"))
                .contains(
                        "FOREIGN KEY (snapshot_id, runtime_id, report_id)",
                        "test_report(snapshot_id, runtime_id, id)");
        assertThat(fks.get("fk_gj_batch")).contains("grade_batch");
        var source =
                db.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                            + " conrelid='public.grade_job'::regclass AND conname='ck_gj_source'",
                        String.class);
        assertThat(source)
                .contains(
                        "batch_id IS NOT NULL",
                        "sample_code IS NOT NULL",
                        "repeat_no IS NOT NULL",
                        "report_id IS NULL",
                        "report_hash IS NULL",
                        "batch_id IS NULL",
                        "sample_code IS NULL",
                        "repeat_no IS NULL",
                        "report_id IS NOT NULL",
                        "report_hash IS NOT NULL",
                        "input_hash IS NOT NULL",
                        "input_hash IS NULL");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid="
                                        + "'public.grade_job'::regclass AND conname='uk_gj_report'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE conrelid="
                                        + "'public.grade_job'::regclass AND conname='fk_gj_batch'",
                                Integer.class))
                .isEqualTo(1);
    }

    /** 실제 TEST 접수가 아직 차단되어 이벤트를 삽입하지 않고 V15 분기를 보존한 V22 물리식만 검사한다. */
    @Test
    void deadlineEventPhysicalShapeOnlyUntilGenuineTestJobExists() {
        String shape =
                db.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conrelid='public.grade_event'::regclass"
                                + " AND conname='ck_grade_event_shape'",
                        String.class);
        assertThat(shape).isNotNull();
        assertThat(shape)
                .contains(
                        "JOB_DEADLINE_EXPIRED",
                        "JOB_ACTIVATED",
                        "JOB_INPUT_REJECTED",
                        "JOB_SOURCE_CANCELLED",
                        "JOB_LEASE_RECLAIMED");
        assertThat(shape)
                .contains(
                        "COMPLETE_APPLIED",
                        "COMPLETE_REJECTED",
                        "RECOVERY_EXPIRED",
                        "WORKER",
                        "SYSTEM",
                        "attempt_no IS NULL",
                        "attempt_no IS NOT NULL",
                        "request_id IS NULL",
                        "request_id IS NOT NULL");
        assertThat(shape)
                .containsPattern(
                        "(?s)JOB_LEASE_RECLAIMED.*JOB_DEADLINE_EXPIRED"
                                + ".*attempt_no IS NULL.*request_id IS NULL");
        assertThat(shape)
                .containsPattern(
                        "(?s)COMPLETE_APPLIED.*COMPLETE_REJECTED"
                                + ".*attempt_no IS NOT NULL.*request_id IS NOT NULL"
                                + ".*RECOVERY_EXPIRED.*attempt_no IS NOT NULL.*request_id IS NULL");
        assertThat(shape.split("JOB_DEADLINE_EXPIRED", -1)).hasSize(2);
    }

    /** 원래 참가자·원본을 실제 서비스로 고정한 제안의 FK·해시·출처 변조와 삭제를 PG가 거절한다. */
    @Test
    void genuineProposalPinsCannotBeChangedOrDeleted() throws Exception {
        playtestPolicy();
        String a =
                account("schema-report-a-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        String b =
                account("schema-report-b-" + UUID.randomUUID() + "@example.invalid")
                        .path("accessToken")
                        .asText();
        UUID key =
                invitation(
                        List.of(
                                invitations.getMemberIdentity(a, UUID.randomUUID()).memberKey(),
                                invitations.getMemberIdentity(b, UUID.randomUUID()).memberKey()));
        for (String token : List.of(a, b)) {
            var notice = invitations.getConsentNotice(token, key, UUID.randomUUID());
            invitations.acceptInvitation(
                    token,
                    key,
                    1,
                    revision(key),
                    notice.policyCode(),
                    notice.noticeHash(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }
        investigation.ready(a, key, revision(key), true, UUID.randomUUID(), UUID.randomUUID());
        investigation.ready(b, key, revision(key), true, UUID.randomUUID(), UUID.randomUUID());
        investigation.heartbeat(a, key, UUID.randomUUID());
        investigation.heartbeat(b, key, UUID.randomUUID());
        investigation.start(a, key, revision(key), UUID.randomUUID(), UUID.randomUUID());
        String culprit =
                db.queryForObject(
                        "SELECT s.payload->'resources'->'persons'->0->>'code'"
                                + " FROM review_snapshot s JOIN play_test t ON t.snapshot_id=s.id"
                                + " WHERE t.test_key=?",
                        String.class,
                        key);
        var text =
                JSON.createObjectNode()
                        .put("culpritCode", culprit)
                        .put("method", "합성 방법")
                        .put("time", "합성 시각")
                        .put("motive", "합성 동기")
                        .put("evidence", "합성 근거");
        reports.edit(a, key, revision(key), 0, text, UUID.randomUUID(), UUID.randomUUID());
        reports.propose(a, key, revision(key), 1, UUID.randomUUID(), UUID.randomUUID());
        UUID reportKey = reports.report(b, key, UUID.randomUUID()).proposal().reportKey();
        Map<String, Object> pinned =
                db.queryForMap(
                        "SELECT"
                            + " id,test_id,snapshot_id,runtime_id,payload_hash,source_draft_rev,state"
                            + " FROM test_report WHERE report_key=?",
                        reportKey);
        long id = ((Number) pinned.get("id")).longValue();
        assertThat(pinned).containsEntry("source_draft_rev", 1L).containsEntry("state", "PROPOSED");
        assertThat(pinned.get("payload_hash")).isEqualTo(SnapshotJson.hash(text));
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE test_report SET payload_hash=? WHERE id=?",
                                        "b".repeat(64),
                                        id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE test_report SET snapshot_id=snapshot_id+1"
                                                + " WHERE id=?",
                                        id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE test_report SET proposer_id=proposer_id+1"
                                                + " WHERE id=?",
                                        id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () -> db.update("UPDATE test_report SET state='ACCEPTED' WHERE id=?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM test_report WHERE id=?", id))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForMap(
                                "SELECT id,test_id,snapshot_id,runtime_id,payload_hash,"
                                        + "source_draft_rev,state FROM test_report WHERE id=?",
                                id))
                .isEqualTo(pinned);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM grade_job WHERE report_id=?",
                                Integer.class,
                                id))
                .isZero();

        reports.edit(
                b,
                key,
                revision(key),
                1,
                text.deepCopy().put("method", "합성 수정"),
                UUID.randomUUID(),
                UUID.randomUUID());
        assertThat(db.queryForObject("SELECT state FROM test_report WHERE id=?", String.class, id))
                .isEqualTo("INVALIDATED");
        db.update(
                "UPDATE test_report SET payload_cipher=NULL,payload_hash=NULL,"
                        + "purged_at=clock_timestamp() WHERE id=?",
                id);
        assertThat(
                        db.queryForMap(
                                "SELECT report_key,test_id,snapshot_id,runtime_id,"
                                    + "source_draft_rev,proposer_id,state,payload_cipher,payload_hash,purged_at"
                                    + " FROM test_report WHERE id=?",
                                id))
                .containsEntry("report_key", reportKey)
                .containsEntry("test_id", pinned.get("test_id"))
                .containsEntry("snapshot_id", pinned.get("snapshot_id"))
                .containsEntry("runtime_id", pinned.get("runtime_id"))
                .containsEntry("source_draft_rev", 1L)
                .containsEntry("state", "INVALIDATED")
                .containsEntry("payload_cipher", null)
                .containsEntry("payload_hash", null)
                .containsKey("purged_at");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE test_report SET payload_hash=? WHERE id=?",
                                        "b".repeat(64),
                                        id))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * @param key 실제 초대 키
     * @return 이미 저장된 서버 수정번호
     */
    private long revision(UUID key) {
        return db.queryForObject("SELECT rev FROM play_test WHERE test_key=?", Long.class, key);
    }
}
