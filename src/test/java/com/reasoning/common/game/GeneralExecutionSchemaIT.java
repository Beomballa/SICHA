package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.grading.GradeSchemaIT;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Set;
import java.util.stream.Collectors;

/** 폐기형 PostgreSQL에서 실제 일반 실행의 부모·불변 경계를 검사한다. */
class GeneralExecutionSchemaIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void open() {
        postgres = GradeSchemaIT.startDatabase();
        jdbc = GradeSchemaIT.jdbc(postgres);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(version::integer) FROM public.flyway_schema_history"
                                        + " WHERE success",
                                Integer.class))
                .isEqualTo(43);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    @Test
    void realParentsAndImmutableTriggers() {
        for (String table : Set.of("game_room", "game_member", "game_report")) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT to_regclass(?)", String.class, "public." + table))
                    .isEqualTo(table);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT obj_description(?::regclass,'pg_class')",
                                    String.class,
                                    "public." + table))
                    .containsPattern("[가-힣]");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_trigger WHERE tgrelid=?::regclass AND"
                                            + " NOT tgisinternal AND tgname LIKE 'tr_general_%'",
                                    Integer.class, "public." + table))
                    .isEqualTo(2);
        }
        var fks =
                jdbc.queryForList(
                        """
                        SELECT c.conname FROM pg_constraint c
                        WHERE c.contype='f' AND c.conrelid IN ('public.game_room'::regclass,
                          'public.game_member'::regclass,'public.game_report'::regclass)
                        """,
                        String.class);
        assertThat(fks)
                .contains(
                        "fk_gr_story",
                        "fk_gr_snapshot",
                        "fk_gr_pair",
                        "fk_gm_identity",
                        "fk_gm_presence",
                        "fk_grp_room",
                        "fk_grp_proposer",
                        "fk_grp_accepter");
    }

    @Test
    void sourceAndAcceptanceConstraintsDoNotAcceptNull() {
        var constraints =
                jdbc.queryForList(
                        """
                        SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint
                        WHERE conrelid IN ('public.game_room'::regclass,'public.game_report'::regclass)
                        """);
        var names =
                constraints.stream()
                        .map(c -> (String) c.get("conname"))
                        .collect(Collectors.toSet());
        assertThat(names)
                .contains(
                        "ck_gr_purpose",
                        "ck_gr_clock",
                        "ck_gr_terminal",
                        "ck_grp_accept",
                        "ck_grp_purge",
                        "ck_grp_grade");
        assertThat(
                        constraints.stream()
                                .filter(c -> c.get("conname").equals("ck_grp_accept"))
                                .map(c -> (String) c.get("definition"))
                                .findFirst()
                                .orElseThrow())
                .contains("grade_deadline_at IS NOT NULL", "ordinal IS NOT NULL");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO public.game_room(room_key,purpose,mode,qualification_kind,story_id,
                                          version_id,snapshot_id,runtime_id,runtime_epoch,config_hash,rubric_hash,
                                          policy_id,play_rev,state,difficulty,limit_sec,max_attempts,max_hints,lobby_until)
                                        VALUES (gen_random_uuid(),'PUBLIC','COOP','BLIND',1,
                                          1,1,1,0,repeat('a',64),repeat('a',64),1,0,'WAITING',1,300,5,3,
                                          clock_timestamp()+interval '30 minutes')
                                        """))
                .hasStackTraceContaining("game purpose policy mismatch");
    }

    @Test
    void ordinaryBulkDeletionCannotBypassPrivacyFence() {
        for (String table : Set.of("game_report", "game_member", "game_room")) {
            assertThatThrownBy(() -> jdbc.execute("TRUNCATE public." + table + " CASCADE"))
                    .hasMessageContaining("general execution truncate forbidden");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT"
                                    + " public.valid_friendly_policy_document('{}'::jsonb,'SYNTHETIC')",
                                Boolean.class))
                .isFalse();
    }
}
