package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.GradeSchemaIT;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 폐기형 PostgreSQL에서 PUBLIC 공개 근거와 행위의 DB 경계를 검사한다. */
class PublicGameIT {
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
    void releaseRequiresRealParentsAndRejectsTruncation() {
        assertThat(
                        db.queryForObject(
                                "SELECT max(version::integer) FROM flyway_schema_history WHERE"
                                        + " success",
                                Integer.class))
                .isEqualTo(43);
        assertThat(db.queryForObject("SELECT to_regclass('public.story_release')", String.class))
                .isEqualTo("story_release");
        assertThat(
                        db.queryForList(
                                "SELECT conname FROM pg_constraint WHERE"
                                        + " conrelid='public.story_release'::regclass",
                                String.class))
                .contains(
                        "fk_release_snapshot",
                        "uk_release_snapshot",
                        "ck_release_bundle",
                        "ck_release_block");
        assertThatThrownBy(() -> db.execute("TRUNCATE public.story_release"))
                .hasMessageContaining("release is durable");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        """
                                        INSERT INTO story_release(version_id,snapshot_id,source_rev,runtime_id,runtime_epoch,
                                          config_hash,evidence_hash,bundle_data,created_by)
                                        VALUES (777777,777777,0,777777,0,repeat('a',64),repeat('b',64),
                                          jsonb_build_object('formatNo',1,'gateVersion','STORY-RELEASE-01',
                                            'snapshotId','777777','sourceRev','0','evidenceHash',repeat('b',64),
                                            'recordIds','{}'::jsonb,'resolutionRecordIds','[]'::jsonb,
                                            'runtime','{}'::jsonb,'checks','[]'::jsonb),777777)
                                        """))
                .hasMessageContaining("foreign key");
        assertThat(db.queryForObject("SELECT count(*) FROM story_release", Integer.class)).isZero();
    }

    @Test
    void publicActionsRequireRealMemberAndQualificationShapeRemainsConstrained() {
        String actions =
                db.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                                + " conrelid='public.game_action'::regclass AND"
                                + " conname='ck_game_action_kind'",
                        String.class);
        assertThat(actions).contains("PUBLIC_CREATE", "PUBLIC_START", "QUALIFICATION_CREATE");
        String actor =
                db.queryForObject(
                        "SELECT pg_get_expr(conbin,conrelid) FROM pg_constraint WHERE"
                                + " conrelid='public.game_action'::regclass AND"
                                + " conname='ck_game_action_actor'",
                        String.class);
        for (String action :
                java.util.List.of(
                        "QUALIFICATION_CREATE",
                        "QUALIFICATION_ACCEPT",
                        "QUALIFICATION_START",
                        "QUALIFICATION_FEEDBACK",
                        "QUALIFICATION_REPORT_EDIT",
                        "QUALIFICATION_REPORT_SUBMIT",
                        "PUBLIC_CREATE",
                        "PUBLIC_START",
                        "PUBLIC_REPORT_EDIT",
                        "PUBLIC_REPORT_SUBMIT",
                        "PUBLIC_HINT_OPEN",
                        "PUBLIC_CLUE_READ",
                        "PUBLIC_FORFEIT")) {
            assertThat(actions).contains(action);
            for (Long admin : new Long[] {null, 1L}) {
                for (Long member : new Long[] {null, 2L}) {
                    boolean expected =
                            action.equals("QUALIFICATION_CREATE")
                                    ? admin != null && member == null
                                    : admin == null && member != null;
                    assertThat(
                                    db.queryForObject(
                                            "SELECT "
                                                    + actor
                                                    + " FROM (SELECT ?::text AS action, ?::bigint"
                                                    + " AS admin_id, ?::bigint AS member_id)"
                                                    + " candidate",
                                            Boolean.class,
                                            action,
                                            admin,
                                            member))
                            .as("행위 %s / 관리자 %s / 회원 %s", action, admin, member)
                            .isEqualTo(expected);
                }
            }
        }
        String audit =
                db.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                                + " conrelid='public.game_audit'::regclass AND"
                                + " conname='ck_game_audit_actor'",
                        String.class);
        assertThat(audit)
                .contains("PUBLIC_CREATE", "PUBLIC_START", "QUALIFICATION_ACCEPT", "CONTENT_READ");
        assertThat(db.queryForObject("SELECT count(*) FROM story_release", Integer.class)).isZero();
    }
}
