package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.reasoning.common.grading.GradeSchemaIT;
import com.reasoning.common.grading.model.SnapshotJson;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/** 원본 스키마에 새 보고서 행위 계약을 적용하고 실제 부모 제약을 보존하는지 확인한다. */
class PublicGameReportIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate db;

    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        db = GradeSchemaIT.jdbc(postgres);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    @Test
    void reportActionAllowlistKeepsQualificationAndPublicContracts() {
        assertThat(
                        db.queryForObject(
                                "SELECT max(version::integer) FROM flyway_schema_history WHERE"
                                        + " success",
                                Integer.class))
                .isEqualTo(44);
        String action =
                db.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                                + " conrelid='public.game_action'::regclass AND"
                                + " conname='ck_game_action_kind'",
                        String.class);
        String audit =
                db.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                                + " conrelid='public.game_audit'::regclass AND"
                                + " conname='ck_game_audit_actor'",
                        String.class);
        assertThat(action)
                .contains("PUBLIC_REPORT_EDIT", "PUBLIC_REPORT_SUBMIT", "QUALIFICATION_ACCEPT");
        assertThat(audit)
                .contains(
                        "PUBLIC_REPORT_EDIT", "PUBLIC_REPORT_SUBMIT", "CONTENT_READ", "UNCHANGED");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " game_action(request_key,member_id,room_id,action,request_hash,result_data)"
                                            + " VALUES"
                                            + " (?,1,999999,'PUBLIC_REPORT_SUBMIT',repeat('a',64),'{}'::jsonb)",
                                        UUID.randomUUID()))
                .hasMessageContaining("foreign key");
    }

    @Test
    void canonicalPayloadHashDoesNotImpersonateFrozenBinding() {
        var draft =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("culpritCode", "P1")
                        .put("method", "  원문  ")
                        .put("time", "첫째 줄\n둘째 줄")
                        .put("motive", "동기")
                        .put("evidence", "증거");
        String payloadHash = SnapshotJson.hash(draft);
        assertThat(SnapshotJson.parse(SnapshotJson.encode(draft))).isEqualTo(draft);
        assertThat(draft.path("method").textValue()).isEqualTo("  원문  ");
        assertThat(payloadHash).matches("[0-9a-f]{64}");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " game_report(report_key,room_id,snapshot_id,source_rev,proposer_id,state,payload_cipher,payload_hash)"
                                            + " VALUES"
                                            + " (?,999999,999999,0,999999,'PROPOSED',decode(repeat('ab',40),'hex'),?)",
                                        UUID.randomUUID(),
                                        payloadHash))
                .hasMessageContaining("report source pin invalid");
    }
}
