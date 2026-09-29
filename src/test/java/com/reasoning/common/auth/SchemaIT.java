package com.reasoning.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class SchemaIT {
    @Test
    @DisplayName("AUTH-V01/ADMIN-V10/STORY-AUDIT: Flyway V1-V6 match approved schema on disposable PostgreSQL")
    void appliesH0Schema() throws Exception {
        DockerImageName image = DockerImageName.parse("postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
            .asCompatibleSubstituteFor("postgres");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(image)) {
            postgres.start();
            Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
            try (Connection connection = postgres.createConnection("")) {
                DatabaseMetaData metadata = connection.getMetaData();
                Set<String> tables = new HashSet<>();
                try (ResultSet rs = metadata.getTables(null, "public", "%", new String[] {"TABLE"})) {
                    while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
                }
                assertThat(tables).containsExactlyInAnyOrder(
                    "flyway_schema_history", "admin_account", "admin_credential", "admin_enrollment",
                    "admin_recovery_code", "admin_auth_audit", "admin_auth_limit", "admin_auth_grant",
                    "admin_session", "spring_session", "spring_session_attributes", "access_history",
                    "story", "story_access", "story_version", "story_person", "review_snapshot", "story_audit",
                    "story_role", "story_pair", "story_clue", "clue_role", "story_hint");
                Map<String, Set<String>> expectedColumns = new HashMap<>(Map.of(
                    "story", Set.of("id", "code", "owner_id", "published_id", "view_yn", "active_yn",
                        "created_at", "updated_at", "edit_rev", "play_rev"),
                    "story_access", Set.of("story_id", "admin_id", "permission", "active_yn", "granted_by",
                        "created_at", "updated_at"),
                    "story_version", Set.of("id", "story_id", "version_no", "edit_rev", "status", "title",
                        "intro", "setting", "difficulty", "est_min", "est_max", "limit_sec", "policy_code",
                        "culprit_code", "method_answer", "time_answer", "motive_answer", "timeline_origin",
                        "reveal_text", "current_snapshot_id", "created_by", "updated_by", "active_yn",
                        "created_at", "updated_at", "source_snapshot_id"),
                    "story_person", Set.of("version_id", "code", "name", "public_text", "secret_text",
                        "active_yn", "created_at", "updated_at"),
                    "review_snapshot", Set.of("id", "version_id", "edit_rev", "format_no", "payload",
                        "request_key", "created_by", "created_at"),
                    "story_audit", Set.of("id", "story_id", "version_id", "actor_id", "target_admin_id",
                        "action", "before_rev", "after_rev", "detail", "created_at"),
                    "story_role", Set.of("version_id", "code", "name", "brief", "active_yn", "created_at", "updated_at"),
                    "story_pair", Set.of("version_id", "role_a", "role_b", "active_yn", "created_at", "updated_at"),
                    "story_clue", Set.of("version_id", "code", "title", "body", "person_code", "scope",
                        "source_text", "active_yn", "created_at", "updated_at"),
                    "clue_role", Set.of("version_id", "clue_code", "role_code", "active_yn", "created_at", "updated_at")));
                expectedColumns.put("story_hint", Set.of("version_id", "code", "level", "body", "active_yn",
                    "created_at", "updated_at"));
                for (var entry : expectedColumns.entrySet()) {
                    Set<String> columns = new HashSet<>();
                    try (ResultSet rs = metadata.getColumns(null, "public", entry.getKey(), "%")) {
                        while (rs.next()) columns.add(rs.getString("COLUMN_NAME"));
                    }
                    assertThat(columns).as(entry.getKey() + " columns").containsExactlyInAnyOrderElementsOf(entry.getValue());
                }
                Map<String, Set<String>> approvedIndexes = new HashMap<>(Map.of(
                    "story", Set.of("pk_story", "uq_story_code", "ix_story_owner"),
                    "story_access", Set.of("pk_story_access", "ix_story_access_admin"),
                    "story_version", Set.of("pk_story_version", "uq_story_version_number",
                        "uq_story_version_story_id", "ux_story_version_work"),
                    "story_person", Set.of("pk_story_person"),
                    "review_snapshot", Set.of("pk_review_snapshot", "uq_review_snapshot_version_id",
                        "uq_review_snapshot_request"),
                    "story_audit", Set.of("pk_story_audit"),
                    "story_role", Set.of("pk_story_role"), "story_pair", Set.of("pk_story_pair"),
                    "story_clue", Set.of("pk_story_clue"),
                    "clue_role", Set.of("pk_clue_role", "ix_clue_role_role")));
                approvedIndexes.put("story_hint", Set.of("pk_story_hint", "uq_story_hint_level"));
                for (var entry : approvedIndexes.entrySet()) {
                    Set<String> indexes = new HashSet<>();
                    try (ResultSet rs = metadata.getIndexInfo(null, "public", entry.getKey(), false, false)) {
                        while (rs.next()) indexes.add(rs.getString("INDEX_NAME"));
                    }
                    assertThat(indexes).as(entry.getKey() + " indexes").containsExactlyInAnyOrderElementsOf(entry.getValue());
                }
                Map<String, Set<String>> approvedForeignKeys = new HashMap<>(Map.of(
                    "story", Set.of("fk_story_owner", "fk_story_published"),
                    "story_access", Set.of("fk_story_access_admin_id", "fk_story_access_granted_by",
                        "fk_story_access_story_id"),
                    "story_version", Set.of("fk_story_version_created_by", "fk_story_version_culprit",
                        "fk_story_version_snapshot", "fk_story_version_source", "fk_story_version_story",
                        "fk_story_version_updated_by"),
                    "story_person", Set.of("fk_story_person_version"),
                    "review_snapshot", Set.of("fk_review_snapshot_creator", "fk_review_snapshot_version"),
                    "story_audit", Set.of("fk_story_audit_story", "fk_story_audit_version",
                        "fk_story_audit_actor", "fk_story_audit_target"),
                    "story_role", Set.of("fk_story_role_version"),
                    "story_pair", Set.of("fk_story_pair_role_a", "fk_story_pair_role_b"),
                    "story_clue", Set.of("fk_story_clue_version", "fk_story_clue_person"),
                    "clue_role", Set.of("fk_clue_role_clue", "fk_clue_role_role")));
                approvedForeignKeys.put("story_hint", Set.of("fk_story_hint_version"));
                int fkCount = 0;
                for (var entry : approvedForeignKeys.entrySet()) {
                    Set<String> foreignKeys = new HashSet<>();
                    try (ResultSet rs = metadata.getImportedKeys(null, "public", entry.getKey())) {
                        while (rs.next()) foreignKeys.add(rs.getString("FK_NAME"));
                    }
                    assertThat(foreignKeys).as(entry.getKey() + " foreign keys")
                        .containsExactlyInAnyOrderElementsOf(entry.getValue());
                    fkCount += foreignKeys.size();
                }
                assertThat(fkCount).isEqualTo(26);
                // 복합 참조의 열 순서와 삭제·갱신 차단 정책까지 검사한다.
                Map<String, List<String>> rolePairFks = new HashMap<>();
                for (String table : List.of("story_role", "story_pair")) {
                    try (ResultSet rs = metadata.getImportedKeys(null, "public", table)) {
                        while (rs.next()) {
                            assertThat(rs.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeyNoAction);
                            assertThat(rs.getInt("UPDATE_RULE")).isEqualTo(DatabaseMetaData.importedKeyNoAction);
                            rolePairFks.computeIfAbsent(rs.getString("FK_NAME"), ignored -> new ArrayList<>())
                                .add(rs.getString("FKCOLUMN_NAME") + "->" + rs.getString("PKTABLE_NAME")
                                    + "." + rs.getString("PKCOLUMN_NAME"));
                        }
                    }
                }
                assertThat(rolePairFks).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "fk_story_role_version", List.of("version_id->story_version.id"),
                    "fk_story_pair_role_a", List.of("version_id->story_role.version_id", "role_a->story_role.code"),
                    "fk_story_pair_role_b", List.of("version_id->story_role.version_id", "role_b->story_role.code")));
                Map<String, Map<Short, String>> clueFks = new HashMap<>();
                for (String table : List.of("story_clue", "clue_role")) {
                    try (ResultSet rs = metadata.getImportedKeys(null, "public", table)) {
                        while (rs.next()) {
                            assertThat(rs.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeyNoAction);
                            assertThat(rs.getInt("UPDATE_RULE")).isEqualTo(DatabaseMetaData.importedKeyNoAction);
                            clueFks.computeIfAbsent(rs.getString("FK_NAME"), ignored -> new java.util.TreeMap<>())
                                .put(rs.getShort("KEY_SEQ"), rs.getString("FKCOLUMN_NAME") + "->"
                                    + rs.getString("PKTABLE_NAME") + "." + rs.getString("PKCOLUMN_NAME"));
                        }
                    }
                }
                assertThat(clueFks).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "fk_story_clue_version", Map.of((short) 1, "version_id->story_version.id"),
                    "fk_story_clue_person", Map.of((short) 1, "version_id->story_person.version_id",
                        (short) 2, "person_code->story_person.code"),
                    "fk_clue_role_clue", Map.of((short) 1, "version_id->story_clue.version_id",
                        (short) 2, "clue_code->story_clue.code"),
                    "fk_clue_role_role", Map.of((short) 1, "version_id->story_role.version_id",
                        (short) 2, "role_code->story_role.code")));
                try (ResultSet rs = metadata.getImportedKeys(null, "public", "story_hint")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("FK_NAME")).isEqualTo("fk_story_hint_version");
                    assertThat(rs.getString("FKCOLUMN_NAME")).isEqualTo("version_id");
                    assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("story_version");
                    assertThat(rs.getString("PKCOLUMN_NAME")).isEqualTo("id");
                    assertThat(rs.getInt("DELETE_RULE")).isEqualTo(DatabaseMetaData.importedKeyNoAction);
                    assertThat(rs.getInt("UPDATE_RULE")).isEqualTo(DatabaseMetaData.importedKeyNoAction);
                    assertThat(rs.next()).isFalse();
                }
                for (var entry : Map.of("story_role", List.of("version_id", "code"),
                        "story_pair", List.of("version_id", "role_a", "role_b"),
                        "story_clue", List.of("version_id", "code"),
                        "clue_role", List.of("version_id", "clue_code", "role_code"),
                        "story_hint", List.of("version_id", "code")).entrySet()) {
                    Map<Short, String> primaryKey = new java.util.TreeMap<>();
                    try (ResultSet rs = metadata.getPrimaryKeys(null, "public", entry.getKey())) {
                        while (rs.next()) {
                            assertThat(rs.getString("PK_NAME")).isEqualTo("pk_" + entry.getKey());
                            primaryKey.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                        }
                    }
                    assertThat(primaryKey.values()).containsExactlyElementsOf(entry.getValue());
                }
                for (String table : List.of("story_clue", "clue_role")) {
                    Map<String, String> definitions = new HashMap<>();
                    try (ResultSet rs = metadata.getColumns(null, "public", table, "%")) {
                        while (rs.next()) definitions.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")
                            + ":" + rs.getInt("COLUMN_SIZE") + ":" + rs.getInt("NULLABLE") + ":" + rs.getString("COLUMN_DEF"));
                    }
                    assertThat(definitions.get("version_id")).isEqualTo("int8:19:0:null");
                    assertThat(definitions.get("active_yn")).isEqualTo("bool:1:0:true");
                    for (String time : List.of("created_at", "updated_at"))
                        assertThat(definitions.get(time)).startsWith("timestamptz:").endsWith(":0:now()");
                    if (table.equals("story_clue")) {
                        assertThat(definitions.get("code")).isEqualTo("varchar:32:0:null");
                        assertThat(definitions.get("title")).isEqualTo("varchar:160:0:null");
                        assertThat(definitions.get("body")).startsWith("text:").endsWith(":1:null");
                        assertThat(definitions.get("person_code")).isEqualTo("varchar:32:1:null");
                        assertThat(definitions.get("scope")).isEqualTo("varchar:8:0:'ROLE'::character varying");
                        assertThat(definitions.get("source_text")).isEqualTo("varchar:400:1:null");
                    } else {
                        assertThat(definitions.get("clue_code")).isEqualTo("varchar:32:0:null");
                        assertThat(definitions.get("role_code")).isEqualTo("varchar:32:0:null");
                    }
                }
                Map<String, String> hintColumns = new HashMap<>();
                try (ResultSet rs = metadata.getColumns(null, "public", "story_hint", "%")) {
                    while (rs.next()) hintColumns.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")
                        + ":" + rs.getInt("COLUMN_SIZE") + ":" + rs.getInt("NULLABLE") + ":" + rs.getString("COLUMN_DEF"));
                }
                assertThat(hintColumns.get("version_id")).isEqualTo("int8:19:0:null");
                assertThat(hintColumns.get("code")).isEqualTo("varchar:32:0:null");
                assertThat(hintColumns.get("level")).startsWith("int2:").endsWith(":0:null");
                assertThat(hintColumns.get("body")).startsWith("text:").endsWith(":1:null");
                assertThat(hintColumns.get("active_yn")).isEqualTo("bool:1:0:true");
                for (String time : List.of("created_at", "updated_at"))
                    assertThat(hintColumns.get(time)).startsWith("timestamptz:").endsWith(":0:now()");
                Map<String, String> clueChecks = new HashMap<>();
                try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                        "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid IN ('public.story_clue'::regclass,'public.clue_role'::regclass) AND contype='c'")) {
                    while (rs.next()) clueChecks.put(rs.getString(1), rs.getString(2));
                }
                assertThat(clueChecks.keySet()).containsExactlyInAnyOrder(
                    "ck_story_clue_code", "ck_story_clue_scope", "ck_story_clue_text");
                assertThat(clueChecks.get("ck_story_clue_code")).contains("^[A-Z0-9_]{1,32}$");
                assertThat(clueChecks.get("ck_story_clue_scope")).contains("COMMON", "ROLE");
                assertThat(clueChecks.get("ck_story_clue_text")).contains("btrim", "title", "body", "12000");
                Map<String, String> hintChecks = new HashMap<>();
                try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                        "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid='public.story_hint'::regclass AND contype='c'")) {
                    while (rs.next()) hintChecks.put(rs.getString(1), rs.getString(2));
                }
                assertThat(hintChecks.keySet()).containsExactlyInAnyOrder("ck_story_hint_code", "ck_story_hint_content");
                assertThat(hintChecks.get("ck_story_hint_code")).contains("^[A-Z0-9_]{1,32}$");
                assertThat(hintChecks.get("ck_story_hint_content")).contains("level", "1", "3", "body", "4000");
                try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='clue_role' "
                        + "AND indexname='ix_clue_role_role'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).contains("(version_id, role_code, clue_code)", "WHERE active_yn");
                    assertThat(rs.next()).isFalse();
                }
                for (String table : List.of("story_role", "story_pair")) {
                    Map<String, String> definitions = new HashMap<>();
                    try (ResultSet rs = metadata.getColumns(null, "public", table, "%")) {
                        while (rs.next()) {
                            definitions.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME") + ":"
                                + rs.getInt("COLUMN_SIZE") + ":" + rs.getInt("NULLABLE") + ":"
                                + rs.getString("COLUMN_DEF"));
                        }
                    }
                    assertThat(definitions.get("version_id")).isEqualTo("int8:19:0:null");
                    assertThat(definitions.get("active_yn")).startsWith("bool:1:0:").endsWith("true");
                    for (String time : List.of("created_at", "updated_at"))
                        assertThat(definitions.get(time)).contains(":0:now()");
                    if (table.equals("story_role")) {
                        assertThat(definitions.get("code")).isEqualTo("varchar:32:0:null");
                        assertThat(definitions.get("name")).isEqualTo("varchar:80:0:null");
                        assertThat(definitions.get("brief")).startsWith("text:").contains(":1:null");
                    } else {
                        assertThat(definitions.get("role_a")).isEqualTo("varchar:32:0:null");
                        assertThat(definitions.get("role_b")).isEqualTo("varchar:32:0:null");
                    }
                }
                Map<String, String> rolePairChecks = new HashMap<>();
                try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                        "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid IN ('public.story_role'::regclass,'public.story_pair'::regclass) AND contype='c'")) {
                    while (rs.next()) rolePairChecks.put(rs.getString(1), rs.getString(2));
                }
                assertThat(rolePairChecks.keySet()).containsExactlyInAnyOrder("ck_story_role_code", "ck_story_role_text", "ck_story_pair_order");
                assertThat(rolePairChecks.get("ck_story_role_code")).contains("^[A-Z0-9_]{1,32}$");
                assertThat(rolePairChecks.get("ck_story_role_text")).contains("btrim", "name", "brief", "4000");
                assertThat(rolePairChecks.get("ck_story_pair_order")).contains("COLLATE \"C\"", "role_a", "role_b", "<");
                Map<String, List<String>> auditForeignKeys = new HashMap<>();
                try (ResultSet rs = metadata.getImportedKeys(null, "public", "story_audit")) {
                    while (rs.next()) {
                        auditForeignKeys.computeIfAbsent(rs.getString("FK_NAME"), ignored -> new ArrayList<>())
                            .add(rs.getString("FKCOLUMN_NAME") + "->" + rs.getString("PKTABLE_NAME")
                                + "." + rs.getString("PKCOLUMN_NAME"));
                    }
                }
                assertThat(auditForeignKeys).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "fk_story_audit_story", List.of("story_id->story.id"),
                    "fk_story_audit_version", List.of("story_id->story_version.story_id",
                        "version_id->story_version.id"),
                    "fk_story_audit_actor", List.of("actor_id->admin_account.id"),
                    "fk_story_audit_target", List.of("target_admin_id->admin_account.id")));
                Map<String, String> auditChecks = new HashMap<>();
                try (var statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("SELECT conname,pg_get_constraintdef(oid) "
                                + "FROM pg_constraint WHERE conrelid='public.story_audit'::regclass AND contype='c'")) {
                    while (rs.next()) auditChecks.put(rs.getString(1), rs.getString(2));
                }
                assertThat(auditChecks.keySet()).containsExactlyInAnyOrder(
                    "ck_story_audit_detail", "ck_story_audit_revision", "ck_sa_system");
                assertThat(auditChecks.get("ck_story_audit_detail")).contains("jsonb_typeof(detail)", "object");
                assertThat(auditChecks.get("ck_story_audit_revision")).contains("before_rev", "after_rev", ">= 0");
                assertThat(auditChecks.get("ck_sa_system")).contains("actor_id IS NOT NULL", "OWNER_EXPIRED",
                    "OWNER_INVALIDATED", "actorKind", "SYSTEM");
                Map<String, String> indexDefinitions = new java.util.HashMap<>();
                try (var statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("SELECT indexname,indexdef FROM pg_indexes "
                                + "WHERE schemaname='public' AND tablename IN ('story','story_access','story_version')")) {
                    while (rs.next()) indexDefinitions.put(rs.getString(1), rs.getString(2));
                }
                assertThat(indexDefinitions.get("ix_story_owner"))
                    .contains("(owner_id, id DESC)", "WHERE active_yn");
                assertThat(indexDefinitions.get("ix_story_access_admin"))
                    .contains("(admin_id, story_id, permission)", "WHERE active_yn");
                assertThat(indexDefinitions.get("ux_story_version_work"))
                    .contains("CREATE UNIQUE INDEX", "(story_id)", "WHERE", "DRAFT", "REVIEW", "READY");
                Set<String> historyColumns = new HashSet<>();
                try (ResultSet rs = metadata.getColumns(null, "public", "flyway_schema_history", "%")) {
                    while (rs.next()) historyColumns.add(rs.getString("COLUMN_NAME"));
                }
                assertThat(historyColumns).containsExactlyInAnyOrder("installed_rank", "version", "description", "type",
                    "script", "checksum", "installed_by", "installed_on", "execution_time", "success");
                try (ResultSet rs = metadata.getIndexInfo(null, "public", "flyway_schema_history", false, false)) {
                    Set<String> indexes = new HashSet<>();
                    while (rs.next()) indexes.add(rs.getString("INDEX_NAME"));
                    assertThat(indexes).contains("flyway_schema_history_pk", "flyway_schema_history_s_idx");
                }
                try (ResultSet rs = metadata.getIndexInfo(null, "public", "admin_enrollment", true, false)) {
                    Set<String> indexes = new HashSet<>();
                    while (rs.next()) indexes.add(rs.getString("INDEX_NAME"));
                    assertThat(indexes).contains("uq_admin_enrollment_boot");
                }
                long owner;
                try (var insert = connection.prepareStatement("INSERT INTO admin_account(account_key) "
                        + "VALUES (?) RETURNING id")) {
                    insert.setObject(1, UUID.randomUUID());
                    try (ResultSet rs = insert.executeQuery()) { rs.next(); owner = rs.getLong(1); }
                }
                long story;
                try (var insert = connection.prepareStatement("INSERT INTO story(code,owner_id) "
                        + "VALUES ('H0_SCHEMA',?) RETURNING id")) {
                    insert.setLong(1, owner);
                    try (ResultSet rs = insert.executeQuery()) { rs.next(); story = rs.getLong(1); }
                }
                try (var brokenAccess = connection.prepareStatement("INSERT INTO story_access"
                        + "(story_id,admin_id,permission,granted_by) VALUES (?,?,'EDIT',?)")) {
                    brokenAccess.setLong(1, story);
                    brokenAccess.setLong(2, owner + 1000);
                    brokenAccess.setLong(3, owner);
                    assertThatThrownBy(brokenAccess::executeUpdate).hasMessageContaining("fk_story_access_admin_id");
                }
                long otherStory;
                try (var insert = connection.prepareStatement("INSERT INTO story(code,owner_id) "
                        + "VALUES ('H0_OTHER',?) RETURNING id")) {
                    insert.setLong(1, owner);
                    try (ResultSet rs = insert.executeQuery()) { rs.next(); otherStory = rs.getLong(1); }
                }
                long otherVersion;
                try (var insert = connection.prepareStatement("INSERT INTO story_version("
                        + "story_id,version_no,title,policy_code,created_by,updated_by) "
                        + "VALUES (?,1,'Fixture','H0_POLICY',?,?) RETURNING id")) {
                    insert.setLong(1, otherStory);
                    insert.setLong(2, owner);
                    insert.setLong(3, owner);
                    try (ResultSet rs = insert.executeQuery()) { rs.next(); otherVersion = rs.getLong(1); }
                }
                try (var brokenPublished = connection.prepareStatement("UPDATE story SET published_id=? WHERE id=?")) {
                    brokenPublished.setLong(1, otherVersion);
                    brokenPublished.setLong(2, story);
                    assertThatThrownBy(brokenPublished::executeUpdate).hasMessageContaining("fk_story_published");
                }
                long ownVersion;
                try (var insert = connection.prepareStatement("INSERT INTO story_version("
                        + "story_id,version_no,title,policy_code,created_by,updated_by) "
                        + "VALUES (?,1,'Fixture','H0_POLICY',?,?) RETURNING id")) {
                    insert.setLong(1, story);
                    insert.setLong(2, owner);
                    insert.setLong(3, owner);
                    try (ResultSet rs = insert.executeQuery()) { rs.next(); ownVersion = rs.getLong(1); }
                }
                try (var insert = connection.prepareStatement("INSERT INTO story_audit"
                        + "(story_id,version_id,actor_id,action,before_rev,after_rev,detail) "
                        + "VALUES (?,?,?,'SECTION_UPDATED',0,1,?::jsonb)")) {
                    insert.setLong(1, story);
                    insert.setLong(2, ownVersion);
                    insert.setLong(3, owner);
                    insert.setString(4, "{\"revisionScope\":\"CONTENT\"}");
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                    insert.setLong(2, otherVersion);
                    assertThatThrownBy(insert::executeUpdate).hasMessageContaining("fk_story_audit_version");
                    insert.setLong(2, ownVersion);
                    insert.setLong(3, owner + 1000);
                    assertThatThrownBy(insert::executeUpdate).hasMessageContaining("fk_story_audit_actor");
                    insert.setLong(3, owner);
                    insert.setString(4, "[]");
                    assertThatThrownBy(insert::executeUpdate).hasMessageContaining("ck_story_audit_detail");
                }
                try (var statement = connection.createStatement()) {
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_audit"
                        + "(story_id,actor_id,action,before_rev,detail) VALUES (" + story
                        + "," + owner + ",'SECTION_UPDATED',-1,'{}')"))
                        .hasMessageContaining("ck_story_audit_revision");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_audit"
                        + "(story_id,actor_id,action,after_rev,detail) VALUES (" + story
                        + "," + owner + ",'SECTION_UPDATED',-1,'{}')"))
                        .hasMessageContaining("ck_story_audit_revision");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_audit"
                        + "(story_id,actor_id,target_admin_id,action,detail) VALUES (" + story
                        + "," + owner + "," + (owner + 1000) + ",'ACCESS_GRANTED','{}')"))
                        .hasMessageContaining("fk_story_audit_target");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_audit"
                        + "(story_id,action,detail) VALUES (" + story + ",'SECTION_UPDATED','{}')"))
                        .hasMessageContaining("ck_sa_system");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_audit"
                        + "(story_id,action,detail) VALUES (" + story
                        + ",'OWNER_EXPIRED','{\"actorKind\":\"ADMIN\"}')"))
                        .hasMessageContaining("ck_sa_system");
                    assertThat(statement.executeUpdate("INSERT INTO story_audit(story_id,action,detail) VALUES ("
                        + story + ",'OWNER_EXPIRED','{\"actorKind\":\"SYSTEM\"}')")).isEqualTo(1);
                    // DB 직접 입력도 예약 키, 양 끝 복합 참조, ASCII 정순 및 원고 길이를 강제한다.
                    assertThat(statement.executeUpdate("INSERT INTO story_role(version_id,code,name,brief) VALUES ("
                        + ownVersion + ",'A','역할',NULL),(" + ownVersion + ",'Z','다른 역할',repeat('가',4000))"))
                        .isEqualTo(2);
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + ownVersion + ",'lower','역할')")).hasMessageContaining("ck_story_role_code");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + ownVersion + ",'EMPTY','   ')")).hasMessageContaining("ck_story_role_text");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name,brief) VALUES ("
                        + ownVersion + ",'LONG','역할',repeat('가',4001))"))
                        .hasMessageContaining("ck_story_role_text");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + ownVersion + ",'" + "X".repeat(33) + "','역할')"))
                        .hasMessageContaining("value too long");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + ownVersion + ",'LONG_NAME',repeat('가',81))"))
                        .hasMessageContaining("value too long");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + otherVersion + ",'A',NULL)")).hasMessageContaining("null value");
                    assertThat(statement.executeUpdate("INSERT INTO story_pair(version_id,role_a,role_b) VALUES ("
                        + ownVersion + ",'A','Z')")).isEqualTo(1);
                    try (ResultSet rs = statement.executeQuery("SELECT r.active_yn,p.active_yn,r.brief IS NULL,"
                            + "r.created_at IS NOT NULL,p.updated_at IS NOT NULL FROM story_role r "
                            + "JOIN story_pair p ON p.version_id=r.version_id AND p.role_a=r.code "
                            + "WHERE r.version_id=" + ownVersion + " AND r.code='A'")) {
                        assertThat(rs.next()).isTrue();
                        for (int column = 1; column <= 5; column++) assertThat(rs.getBoolean(column)).isTrue();
                        assertThat(rs.next()).isFalse();
                    }
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_pair(version_id,role_a,role_b) VALUES ("
                        + ownVersion + ",'A','A')")).hasMessageContaining("ck_story_pair_order");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_pair(version_id,role_a,role_b) VALUES ("
                        + ownVersion + ",'Z','A')")).hasMessageContaining("ck_story_pair_order");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_pair(version_id,role_a,role_b) VALUES ("
                        + ownVersion + ",'A','ZZ')")).hasMessageContaining("fk_story_pair_role_b");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_pair(version_id,role_a,role_b) VALUES ("
                        + otherVersion + ",'A','Z')")).hasMessageContaining("fk_story_pair_role_a");
                    assertThat(statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + otherVersion + ",'B','다른 사건 역할')")).isEqualTo(1);
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_pair(version_id,role_a,role_b) VALUES ("
                        + ownVersion + ",'A','B')")).hasMessageContaining("fk_story_pair_role_b");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + (otherVersion + 100000) + ",'NEW','역할')"))
                        .hasMessageContaining("fk_story_role_version");
                    assertThat(statement.executeUpdate("UPDATE story_role SET active_yn=false WHERE version_id="
                        + ownVersion + " AND code='A'")).isEqualTo(1);
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_role(version_id,code,name) VALUES ("
                        + ownVersion + ",'A','다시 생성')")).hasMessageContaining("pk_story_role");
                    assertThat(statement.executeUpdate("UPDATE story_pair SET active_yn=false WHERE version_id="
                        + ownVersion + " AND role_a='A' AND role_b='Z'")).isEqualTo(1);
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_pair(version_id,role_a,role_b) VALUES ("
                        + ownVersion + ",'A','Z')")).hasMessageContaining("pk_story_pair");
                    assertThat(statement.executeUpdate("INSERT INTO story_person(version_id,code,name) VALUES ("
                        + ownVersion + ",'P','인물'),(" + otherVersion + ",'Q','다른 버전 인물')")).isEqualTo(2);
                    assertThat(statement.executeUpdate("INSERT INTO story_clue(version_id,code,title,body,person_code) VALUES ("
                        + ownVersion + ",'A','단서',repeat('가',12000),'P')")).isEqualTo(1);
                    assertThat(statement.executeUpdate("INSERT INTO clue_role(version_id,clue_code,role_code) VALUES ("
                        + ownVersion + ",'A','A')")).isEqualTo(1);
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_clue(version_id,code,title) VALUES ("
                        + ownVersion + ",'lower','제목')")).hasMessageContaining("ck_story_clue_code");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_clue(version_id,code,title,scope) VALUES ("
                        + ownVersion + ",'BAD_SCOPE','제목','HIDDEN')")).hasMessageContaining("ck_story_clue_scope");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_clue(version_id,code,title) VALUES ("
                        + ownVersion + ",'BLANK','   ')")).hasMessageContaining("ck_story_clue_text");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_clue(version_id,code,title) VALUES ("
                        + ownVersion + ",'LONG_TITLE',repeat('가',161))"))
                        .hasMessageContaining("value too long");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_clue(version_id,code,title) VALUES ("
                        + ownVersion + ",'" + "X".repeat(33) + "','제목')"))
                        .hasMessageContaining("value too long");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_clue(version_id,code,title,body) VALUES ("
                        + ownVersion + ",'LONG_BODY','제목',repeat('가',12001))"))
                        .hasMessageContaining("ck_story_clue_text");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_clue(version_id,code,title,person_code) VALUES ("
                        + ownVersion + ",'CROSS_PERSON','제목','Q')"))
                        .hasMessageContaining("fk_story_clue_person");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO clue_role(version_id,clue_code,role_code) VALUES ("
                        + ownVersion + ",'A','B')")).hasMessageContaining("fk_clue_role_role");
                    assertThat(statement.executeUpdate("INSERT INTO story_clue(version_id,code,title) VALUES ("
                        + otherVersion + ",'OTHER','다른 버전 단서')")).isEqualTo(1);
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO clue_role(version_id,clue_code,role_code) VALUES ("
                        + ownVersion + ",'OTHER','A')")).hasMessageContaining("fk_clue_role_clue");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO clue_role(version_id,clue_code,role_code) VALUES ("
                        + otherVersion + ",'OTHER','A')")).hasMessageContaining("fk_clue_role_role");
                    assertThat(statement.executeUpdate("INSERT INTO story_hint(version_id,code,level,body) VALUES ("
                        + ownVersion + ",'A',1,repeat('가',4000))")).isEqualTo(1);
                    assertThat(statement.executeUpdate("UPDATE story_hint SET active_yn=false WHERE version_id="
                        + ownVersion + " AND code='A'")).isEqualTo(1);
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_hint(version_id,code,level) VALUES ("
                        + ownVersion + ",'A',2)")).hasMessageContaining("pk_story_hint");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_hint(version_id,code,level) VALUES ("
                        + ownVersion + ",'B',1)")).hasMessageContaining("uq_story_hint_level");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_hint(version_id,code,level) VALUES ("
                        + ownVersion + ",'lower',2)")).hasMessageContaining("ck_story_hint_code");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_hint(version_id,code,level) VALUES ("
                        + ownVersion + ",'BAD_ZERO',0)")).hasMessageContaining("ck_story_hint_content");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_hint(version_id,code,level,body) VALUES ("
                        + ownVersion + ",'LONG',2,repeat('가',4001))")).hasMessageContaining("ck_story_hint_content");
                    assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO story_hint(version_id,code,level) VALUES ("
                        + (otherVersion + 100000) + ",'MISSING',1)")).hasMessageContaining("fk_story_hint_version");
                    assertThat(statement.executeUpdate("INSERT INTO story_hint(version_id,code,level,body) VALUES ("
                        + otherVersion + ",'A',1,NULL)")).isEqualTo(1);
                }
            }
        }
    }
}
