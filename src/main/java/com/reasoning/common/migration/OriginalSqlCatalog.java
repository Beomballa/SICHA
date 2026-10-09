package com.reasoning.common.migration;

import com.reasoning.common.util.CommonUtil;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 변경하지 않은 역사적 SQL과 독립적인 원본 UTF-8 해시를 소유한다. 파일 읽기와 대체 목록은 없다. */
public final class OriginalSqlCatalog {
    private OriginalSqlCatalog() {}

    /** 원본 basename과 전체 SQL 문자열의 불변 값이다. */
    public record Entry(String name, String sql) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sql, "sql");
        }
    }

    private static final Map<String, String> EXPECTED =
            Map.ofEntries(
                    Map.entry(
                            "V1__h0_admin_auth.sql",
                            "499026fe40f5559e37cf62b9e2a7b37d06d7e3baebd36a67af585a36a9028e34"),
                    Map.entry(
                            "V2__h0_admin_relation_prerequisites.sql",
                            "e8cdc9dc3d63fbb74d6a31cf27df71f296ada10827cfcada0de6c5f2e6e63cbe"),
                    Map.entry(
                            "V3__h1_story_audit.sql",
                            "ccac2a3309612e2a881602b49a78ee9c0bf8c0ace51d082ede0bf1588df2edfc"),
                    Map.entry(
                            "V4__h1_story_roles_and_pairs.sql",
                            "c1bc78575020ca898483607a103d82368f49c9f72b7b07bdc91804337e1db150"),
                    Map.entry(
                            "V5__h1_story_clues_and_roles.sql",
                            "b8ae790ea1692d78873cae524232aeaa8a37b3ecf630076c6d215c29e6f0dc1b"),
                    Map.entry(
                            "V6__h1_story_hints.sql",
                            "302e6d9bd54502cb567af0bd81ad318d76d3de32bb3adaa8a1110f11afd7e1fa"),
                    Map.entry(
                            "V7__h1_story_events.sql",
                            "1ee149ec7647fe9269554c7dce414ac2e1460f8a8fcbd64f943a0d0cbc46144e"),
                    Map.entry(
                            "V8__h1_story_facts.sql",
                            "b7e69dc4ae299bff4453a2dbf20586a089ff692359ef6cd39674184d92f75093"),
                    Map.entry(
                            "V9__h1_story_rubrics_and_clues.sql",
                            "9589856583be0521daed389b6a991321877ed0c5e5a84b13bf0ed0b159190d5e"),
                    Map.entry(
                            "V10__h1_grade_samples.sql",
                            "0b13577cf98cdd89430db3aaf08d3ffa8dcdceaf517f97c0267198a82d12bc96"),
                    Map.entry(
                            "V11__h1_story_owner_transfer.sql",
                            "3568b3af27a0603ed0826af20cf0a24f90bce97ea8b6694e2c825565b9b2d67d"),
                    Map.entry(
                            "V12__h2_grade_terms.sql",
                            "774dc683dd4163e822e376b3f80a703405bbb063f6ecbc47c6c359a59ba6e71e"),
                    Map.entry(
                            "V13__h2_fixture_jobs.sql",
                            "7fcec0288a7f257b2d8cd7df4ba323a008d3ee00093f80119ff5cd913bec9b4e"),
                    Map.entry(
                            "V14__h2_grade_audit.sql",
                            "929a5e2f864e61dac27bab3e9a13b2a2cf367d4b8fbdf76962720375d3f92c93"),
                    Map.entry(
                            "V15__h2_lease_reclaim_audit.sql",
                            "24c9050424431e6e70f0622c8cc03c9afd638b7f3a8b2c84884793699c25c816"),
                    Map.entry(
                            "V16__h3_review_records.sql",
                            "3e51fefadc803df34939ca740257750b8350a368114914022da09da2bf5c8bcc"),
                    Map.entry(
                            "V17__h2_batch_action_audit_issues.sql",
                            "5e1c1f7649856360b3fa9396456cb50210507841fb0cd2f41df02943cfc8a02a"),
                    Map.entry(
                            "V18__h3_grade_evidence.sql",
                            "b030e65b4f3f6a229d95d17c89dd01fbfe29cea79a13b1637334c58216c195c2"),
                    Map.entry(
                            "V19__h4_local_member_auth.sql",
                            "5adb2c16378f7dc8fa01a237d431f05a6fc3e12d968b9a0a87a07be28ad34919"),
                    Map.entry(
                            "V20__h5_playtest_invitations.sql",
                            "79232e8dfe5b5ac83369ba8ac9a1a7b009090cb31dad2def463982f76d60a8bc"),
                    Map.entry(
                            "V21__h5_test_hints.sql",
                            "911236943b2e10fac1189a501a02b1dd65b039227634291b8c4f0e77b1b11d9a"),
                    Map.entry(
                            "V22__h5_report_result.sql",
                            "bb1852bf9d9cbe150355f678843a128eb293092224b4f50226c26cd9f07e600b"),
                    Map.entry(
                            "V23__h5_test_job_guard_scope.sql",
                            "3d793c8cf4b11865af878bc3a31afe7a0e4e060346c255a25efc418cd4288d0d"));

    private static final List<Entry> ENTRIES =
            validate(
                    List.of(
                            new Entry("V1__h0_admin_auth.sql", sql1()),
                            new Entry("V2__h0_admin_relation_prerequisites.sql", sql2()),
                            new Entry("V3__h1_story_audit.sql", sql3()),
                            new Entry("V4__h1_story_roles_and_pairs.sql", sql4()),
                            new Entry("V5__h1_story_clues_and_roles.sql", sql5()),
                            new Entry("V6__h1_story_hints.sql", sql6()),
                            new Entry("V7__h1_story_events.sql", sql7()),
                            new Entry("V8__h1_story_facts.sql", sql8()),
                            new Entry("V9__h1_story_rubrics_and_clues.sql", sql9()),
                            new Entry("V10__h1_grade_samples.sql", sql10()),
                            new Entry("V11__h1_story_owner_transfer.sql", sql11()),
                            new Entry("V12__h2_grade_terms.sql", sql12()),
                            new Entry("V13__h2_fixture_jobs.sql", sql13()),
                            new Entry("V14__h2_grade_audit.sql", sql14()),
                            new Entry("V15__h2_lease_reclaim_audit.sql", sql15()),
                            new Entry("V16__h3_review_records.sql", sql16()),
                            new Entry("V17__h2_batch_action_audit_issues.sql", sql17()),
                            new Entry("V18__h3_grade_evidence.sql", sql18()),
                            new Entry("V19__h4_local_member_auth.sql", sql19()),
                            new Entry("V20__h5_playtest_invitations.sql", sql20()),
                            new Entry("V21__h5_test_hints.sql", sql21()),
                            new Entry("V22__h5_report_result.sql", sql22()),
                            new Entry("V23__h5_test_job_guard_scope.sql", sql23())));

    /**
     * 완전성과 이름·바이트 해시가 검증된 원본 전체를 반환한다.
     *
     * @return 수정 불가능한 전체 23개 원본 목록
     */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * 부분 목록, 중복·미등록 이름 또는 역사적 텍스트 변경을 시작 전에 거절한다.
     *
     * @param entries null이 아닌 전체 원본 후보; 각 항목도 null이 아니다
     * @return 검증된 수정 불가능한 사본
     * @throws IllegalStateException 원본 이름이나 SHA-256이 일치하지 않는 경우
     */
    static List<Entry> validate(List<Entry> entries) {
        if (entries.size() != EXPECTED.size()) {
            throw new IllegalStateException("원본 SQL 목록이 완전하지 않습니다");
        }
        var names = new HashSet<String>();
        for (Entry entry : entries) {
            if (!names.add(entry.name()) || !EXPECTED.containsKey(entry.name())) {
                throw new IllegalStateException("원본 SQL 이름 불일치: " + entry.name());
            }
            String hash = CommonUtil.sha256(entry.sql().getBytes(StandardCharsets.UTF_8));
            if (!EXPECTED.get(entry.name()).equals(hash)) {
                throw new IllegalStateException("원본 SQL 해시 불일치: " + entry.name());
            }
        }
        return List.copyOf(entries);
    }

    /** V1 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql1() {
        return "CREATE TABLE public.admin_account (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  account_key uuid NOT NULL,\n"
                + "  can_create boolean DEFAULT false NOT NULL,\n"
                + "  can_review boolean DEFAULT false NOT NULL,\n"
                + "  can_publish boolean DEFAULT false NOT NULL,\n"
                + "  can_manage boolean DEFAULT false NOT NULL,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  edit_rev bigint DEFAULT 1 NOT NULL,\n"
                + "  CONSTRAINT pk_admin_account PRIMARY KEY (id),\n"
                + "  CONSTRAINT uq_admin_account_key UNIQUE (account_key),\n"
                + "  CONSTRAINT ck_admin_account_rev CHECK (edit_rev > 0)\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.admin_credential (\n"
                + "  account_id bigint NOT NULL,\n"
                + "  login_cipher text NOT NULL,\n"
                + "  login_hash bytea NOT NULL,\n"
                + "  search_key_ver smallint DEFAULT 1 NOT NULL,\n"
                + "  enroll_gen integer DEFAULT 1 NOT NULL,\n"
                + "  password_hash text,\n"
                + "  mfa_cipher text,\n"
                + "  mfa_verified_at timestamptz,\n"
                + "  last_step bigint,\n"
                + "  enrolled_at timestamptz,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  auth_rev bigint DEFAULT 1 NOT NULL,\n"
                + "  mfa_state varchar(12) DEFAULT 'PENDING' NOT NULL,\n"
                + "  CONSTRAINT pk_admin_credential PRIMARY KEY (account_id),\n"
                + "  CONSTRAINT uq_admin_credential_login UNIQUE (login_hash),\n"
                + "  CONSTRAINT ck_admin_credential_hash CHECK (octet_length(login_hash)=32 AND"
                + " search_key_ver=1 AND enroll_gen>0),\n"
                + "  CONSTRAINT ck_admin_credential_mfa CHECK ((mfa_verified_at IS NULL AND"
                + " last_step IS NULL) OR (mfa_verified_at IS NOT NULL AND last_step IS NOT NULL"
                + " AND last_step>=0 AND mfa_cipher IS NOT NULL)),\n"
                + "  CONSTRAINT ck_admin_credential_complete CHECK (enrolled_at IS NULL OR"
                + " (password_hash IS NOT NULL AND mfa_cipher IS NOT NULL AND mfa_verified_at IS"
                + " NOT NULL AND last_step IS NOT NULL)),\n"
                + "  CONSTRAINT ck_admin_credential_revision CHECK (auth_rev>0 AND mfa_state IN"
                + " ('PENDING','READY','RECOVERY')),\n"
                + "  CONSTRAINT ck_admin_credential_state CHECK ((enrolled_at IS NULL AND"
                + " mfa_state='PENDING') OR (enrolled_at IS NOT NULL AND mfa_state IN"
                + " ('READY','RECOVERY'))),\n"
                + "  CONSTRAINT fk_admin_credential_account FOREIGN KEY (account_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.admin_enrollment (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  registration_key uuid NOT NULL,\n"
                + "  account_id bigint NOT NULL,\n"
                + "  kind varchar(12) NOT NULL,\n"
                + "  generation integer DEFAULT 1 NOT NULL,\n"
                + "  code_hash bytea,\n"
                + "  code_expires_at timestamptz NOT NULL,\n"
                + "  code_consumed_at timestamptz,\n"
                + "  grant_hash bytea,\n"
                + "  grant_expires_at timestamptz,\n"
                + "  verified_by bigint,\n"
                + "  verification_ref varchar(64) NOT NULL,\n"
                + "  verified_at timestamptz NOT NULL,\n"
                + "  revoked_at timestamptz,\n"
                + "  completed_at timestamptz,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_admin_enrollment PRIMARY KEY (id),\n"
                + "  CONSTRAINT uq_admin_enrollment_key UNIQUE (registration_key),\n"
                + "  CONSTRAINT uq_admin_enrollment_account UNIQUE (account_id),\n"
                + "  CONSTRAINT uq_admin_enrollment_code UNIQUE (code_hash),\n"
                + "  CONSTRAINT uq_admin_enrollment_grant UNIQUE (grant_hash),\n"
                + "  CONSTRAINT ck_admin_enrollment_kind CHECK (kind IN ('BOOTSTRAP','INVITE')"
                + " AND generation>0),\n"
                + "  CONSTRAINT ck_admin_enrollment_verifier CHECK ((kind='BOOTSTRAP' AND"
                + " verified_by IS NULL) OR (kind='INVITE' AND verified_by IS NOT NULL)),\n"
                + "  CONSTRAINT ck_admin_enrollment_ref CHECK (verification_ref ~"
                + " '^[A-Za-z0-9_-]{8,64}$'),\n"
                + "  CONSTRAINT ck_admin_enrollment_hash CHECK ((code_hash IS NULL OR"
                + " octet_length(code_hash)=32) AND (grant_hash IS NULL OR"
                + " octet_length(grant_hash)=32)),\n"
                + "  CONSTRAINT ck_admin_enrollment_grant CHECK ((code_consumed_at IS NULL) ="
                + " (grant_expires_at IS NULL)),\n"
                + "  CONSTRAINT ck_admin_enrollment_grant_hash CHECK (grant_hash IS NULL OR"
                + " (code_consumed_at IS NOT NULL AND grant_expires_at IS NOT NULL)),\n"
                + "  CONSTRAINT ck_admin_enrollment_code CHECK (code_consumed_at IS NULL OR"
                + " code_hash IS NULL),\n"
                + "  CONSTRAINT ck_admin_enrollment_end CHECK (completed_at IS NULL OR"
                + " revoked_at IS NULL),\n"
                + "  CONSTRAINT ck_admin_enrollment_ended CHECK ((completed_at IS NULL AND"
                + " revoked_at IS NULL) OR (code_hash IS NULL AND grant_hash IS NULL)),\n"
                + "  CONSTRAINT ck_admin_enrollment_complete CHECK (completed_at IS NULL OR"
                + " (code_consumed_at IS NOT NULL AND grant_expires_at IS NOT NULL)),\n"
                + "  CONSTRAINT fk_admin_enrollment_account FOREIGN KEY (account_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "  CONSTRAINT fk_admin_enrollment_verifier FOREIGN KEY (verified_by)"
                + " REFERENCES public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION\n"
                + ");\n"
                + "CREATE UNIQUE INDEX uq_admin_enrollment_boot ON public.admin_enrollment"
                + " (kind) WHERE kind='BOOTSTRAP';\n"
                + "\n"
                + "CREATE TABLE public.admin_recovery_code (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  account_id bigint NOT NULL,\n"
                + "  set_key uuid NOT NULL,\n"
                + "  code_hash bytea NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  consumed_at timestamptz,\n"
                + "  revoked_at timestamptz,\n"
                + "  CONSTRAINT pk_admin_recovery_code PRIMARY KEY (id),\n"
                + "  CONSTRAINT uq_admin_recovery_code_hash UNIQUE (code_hash),\n"
                + "  CONSTRAINT ck_admin_recovery_code_hash CHECK"
                + " (octet_length(code_hash)=32),\n"
                + "  CONSTRAINT ck_admin_recovery_code_end CHECK (consumed_at IS NULL OR"
                + " revoked_at IS NULL),\n"
                + "  CONSTRAINT fk_admin_recovery_code_account FOREIGN KEY (account_id)"
                + " REFERENCES public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION\n"
                + ");\n"
                + "CREATE INDEX ix_admin_recovery_code_account ON public.admin_recovery_code"
                + " (account_id, set_key);\n"
                + "\n"
                + "CREATE TABLE public.admin_auth_audit (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  actor_kind varchar(12) NOT NULL,\n"
                + "  actor_id bigint,\n"
                + "  actor_ref varchar(64),\n"
                + "  target_id bigint,\n"
                + "  enrollment_id bigint,\n"
                + "  generation integer,\n"
                + "  action varchar(40) NOT NULL,\n"
                + "  outcome varchar(12) NOT NULL,\n"
                + "  reason_code varchar(40),\n"
                + "  request_id uuid NOT NULL,\n"
                + "  route varchar(160) NOT NULL,\n"
                + "  method varchar(8) NOT NULL,\n"
                + "  http_status smallint,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  session_key uuid,\n"
                + "  grant_key uuid,\n"
                + "  change_data jsonb,\n"
                + "  CONSTRAINT pk_admin_auth_audit PRIMARY KEY (id),\n"
                + "  CONSTRAINT ck_admin_auth_audit_kind CHECK (actor_kind IN"
                + " ('ADMIN','ENROLLEE','OFFLINE','ANONYMOUS')),\n"
                + "  CONSTRAINT ck_admin_auth_audit_actor CHECK ((actor_kind IN"
                + " ('ADMIN','ENROLLEE') AND actor_id IS NOT NULL AND actor_ref IS NULL) OR"
                + " (actor_kind='OFFLINE' AND actor_id IS NULL AND actor_ref IS NOT NULL) OR"
                + " (actor_kind='ANONYMOUS' AND actor_id IS NULL AND actor_ref IS NULL)),\n"
                + "  CONSTRAINT ck_admin_auth_audit_result CHECK (outcome IN"
                + " ('COMMITTED','DENIED','FAILED') AND (http_status IS NULL OR http_status"
                + " BETWEEN 100 AND 599)),\n"
                + "  CONSTRAINT ck_admin_auth_audit_generation CHECK ((enrollment_id IS NULL AND"
                + " generation IS NULL) OR (enrollment_id IS NOT NULL AND generation IS NOT NULL"
                + " AND generation>0)),\n"
                + "  CONSTRAINT ck_admin_auth_audit_change CHECK (change_data IS NULL OR"
                + " (jsonb_typeof(change_data)='object' AND"
                + " octet_length(change_data::text)<=4096)),\n"
                + "  CONSTRAINT fk_admin_auth_audit_actor_id FOREIGN KEY (actor_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "  CONSTRAINT fk_admin_auth_audit_target_id FOREIGN KEY (target_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "  CONSTRAINT fk_admin_auth_audit_enroll FOREIGN KEY (enrollment_id)"
                + " REFERENCES public.admin_enrollment (id) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION\n"
                + ");\n"
                + "CREATE INDEX ix_admin_auth_audit_target ON public.admin_auth_audit"
                + " (target_id, id DESC);\n"
                + "\n"
                + "CREATE TABLE public.admin_auth_limit (\n"
                + "  action varchar(16) NOT NULL,\n"
                + "  bucket_kind varchar(8) NOT NULL,\n"
                + "  bucket_hash bytea NOT NULL,\n"
                + "  window_at timestamptz NOT NULL,\n"
                + "  fail_count smallint DEFAULT 0 NOT NULL,\n"
                + "  blocked_until timestamptz,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_admin_auth_limit PRIMARY KEY (action, bucket_kind,"
                + " bucket_hash),\n"
                + "  CONSTRAINT ck_admin_auth_limit_action CHECK (action IN"
                + " ('EXCHANGE','TOTP','PASSWORD','RECOVERY_CODE') AND bucket_kind IN"
                + " ('ACCOUNT','SOURCE')),\n"
                + "  CONSTRAINT ck_admin_auth_limit_count CHECK (octet_length(bucket_hash)=32"
                + " AND fail_count BETWEEN 0 AND 5),\n"
                + "  CONSTRAINT ck_admin_auth_limit_block CHECK ((fail_count<5 AND blocked_until"
                + " IS NULL) OR (fail_count=5 AND blocked_until IS NOT NULL))\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.admin_auth_grant (\n"
                + "  grant_key uuid NOT NULL,\n"
                + "  account_id bigint NOT NULL,\n"
                + "  purpose varchar(16) NOT NULL,\n"
                + "  delivery varchar(8) NOT NULL,\n"
                + "  token_hash bytea,\n"
                + "  auth_rev bigint NOT NULL,\n"
                + "  expires_at timestamptz NOT NULL,\n"
                + "  issued_by bigint,\n"
                + "  verification_ref varchar(64),\n"
                + "  allow_inactive boolean DEFAULT false NOT NULL,\n"
                + "  mfa_cipher text,\n"
                + "  mfa_verified_at timestamptz,\n"
                + "  last_step bigint,\n"
                + "  consumed_at timestamptz,\n"
                + "  revoked_at timestamptz,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_admin_auth_grant PRIMARY KEY (grant_key),\n"
                + "  CONSTRAINT uq_admin_auth_grant_token UNIQUE (token_hash),\n"
                + "  CONSTRAINT ck_admin_auth_grant_purpose CHECK (purpose IN"
                + " ('LOGIN_MFA','PASSWORD_RESET','MFA_RECOVERY') AND delivery IN"
                + " ('CODE','COOKIE') AND auth_rev>0),\n"
                + "  CONSTRAINT ck_admin_auth_grant_hash CHECK (token_hash IS NULL OR"
                + " octet_length(token_hash)=32),\n"
                + "  CONSTRAINT ck_admin_auth_grant_end CHECK (consumed_at IS NULL OR revoked_at"
                + " IS NULL),\n"
                + "  CONSTRAINT ck_admin_auth_grant_live CHECK ((consumed_at IS NULL AND"
                + " revoked_at IS NULL AND token_hash IS NOT NULL) OR ((consumed_at IS NOT NULL"
                + " OR revoked_at IS NOT NULL) AND token_hash IS NULL AND mfa_cipher IS NULL AND"
                + " mfa_verified_at IS NULL AND last_step IS NULL)),\n"
                + "  CONSTRAINT ck_admin_auth_grant_mfa CHECK ((mfa_verified_at IS NULL AND"
                + " last_step IS NULL) OR (purpose='MFA_RECOVERY' AND delivery='COOKIE' AND"
                + " mfa_cipher IS NOT NULL AND mfa_verified_at IS NOT NULL AND last_step IS NOT"
                + " NULL AND last_step>=0)),\n"
                + "  CONSTRAINT ck_admin_auth_grant_secret CHECK (mfa_cipher IS NULL OR"
                + " (purpose='MFA_RECOVERY' AND delivery='COOKIE')),\n"
                + "  CONSTRAINT ck_admin_auth_grant_login CHECK (purpose<>'LOGIN_MFA' OR"
                + " (delivery='COOKIE' AND allow_inactive=false AND issued_by IS NULL)),\n"
                + "  CONSTRAINT ck_admin_auth_grant_offline CHECK (allow_inactive=false OR"
                + " (purpose IN ('PASSWORD_RESET','MFA_RECOVERY') AND issued_by IS NULL AND"
                + " verification_ref IS NOT NULL)),\n"
                + "  CONSTRAINT ck_admin_auth_grant_time CHECK (expires_at>created_at),\n"
                + "  CONSTRAINT fk_admin_auth_grant_account FOREIGN KEY (account_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "  CONSTRAINT fk_admin_auth_grant_issuer FOREIGN KEY (issued_by) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "CREATE UNIQUE INDEX uq_admin_auth_grant_recovery ON public.admin_auth_grant"
                + " (account_id, purpose) WHERE purpose IN ('PASSWORD_RESET','MFA_RECOVERY') AND"
                + " consumed_at IS NULL AND revoked_at IS NULL;\n"
                + "CREATE INDEX ix_admin_auth_grant_expiry ON public.admin_auth_grant"
                + " (expires_at);\n"
                + "\n"
                + "CREATE TABLE public.admin_session (\n"
                + "  session_key uuid NOT NULL,\n"
                + "  account_id bigint NOT NULL,\n"
                + "  sid_hash bytea NOT NULL,\n"
                + "  auth_rev bigint NOT NULL,\n"
                + "  state varchar(10) DEFAULT 'PENDING' NOT NULL,\n"
                + "  started_at timestamptz NOT NULL,\n"
                + "  last_action_at timestamptz NOT NULL,\n"
                + "  expires_at timestamptz NOT NULL,\n"
                + "  reauth_at timestamptz NOT NULL,\n"
                + "  activated_at timestamptz,\n"
                + "  revoked_at timestamptz,\n"
                + "  replaces_key uuid,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_admin_session PRIMARY KEY (session_key),\n"
                + "  CONSTRAINT uq_admin_session_sid UNIQUE (sid_hash),\n"
                + "  CONSTRAINT ck_admin_session_revision CHECK (auth_rev>0 AND"
                + " octet_length(sid_hash)=32 AND state IN ('PENDING','ACTIVE','REVOKED')),\n"
                + "  CONSTRAINT ck_admin_session_state CHECK ((state='PENDING' AND activated_at"
                + " IS NULL AND revoked_at IS NULL) OR (state='ACTIVE' AND activated_at IS NOT"
                + " NULL AND revoked_at IS NULL) OR (state='REVOKED' AND revoked_at IS NOT"
                + " NULL)),\n"
                + "  CONSTRAINT ck_admin_session_time CHECK (expires_at=started_at+interval '8"
                + " hours' AND last_action_at>=started_at AND reauth_at>=started_at),\n"
                + "  CONSTRAINT fk_admin_session_account FOREIGN KEY (account_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "CREATE INDEX ix_admin_session_expiry ON public.admin_session (expires_at);\n"
                + "\n"
                + "CREATE TABLE public.spring_session (\n"
                + "  primary_id char(36) NOT NULL,\n"
                + "  session_id char(36) NOT NULL,\n"
                + "  creation_time bigint NOT NULL,\n"
                + "  last_access_time bigint NOT NULL,\n"
                + "  max_inactive_interval integer NOT NULL,\n"
                + "  expiry_time bigint NOT NULL,\n"
                + "  principal_name varchar(100),\n"
                + "  CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)\n"
                + ");\n"
                + "CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session"
                + " (session_id);\n"
                + "CREATE INDEX spring_session_ix2 ON public.spring_session (expiry_time);\n"
                + "CREATE INDEX spring_session_ix3 ON public.spring_session (principal_name);\n"
                + "\n"
                + "CREATE TABLE public.spring_session_attributes (\n"
                + "  session_primary_id char(36) NOT NULL,\n"
                + "  attribute_name varchar(200) NOT NULL,\n"
                + "  attribute_bytes bytea NOT NULL,\n"
                + "  CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id,"
                + " attribute_name),\n"
                + "  CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)"
                + " REFERENCES public.spring_session (primary_id) ON DELETE CASCADE ON UPDATE NO"
                + " ACTION\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.access_history (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  kind varchar(8) NOT NULL,\n"
                + "  request_id uuid NOT NULL,\n"
                + "  event_key uuid,\n"
                + "  actor_kind varchar(12) NOT NULL,\n"
                + "  actor_key uuid,\n"
                + "  route varchar(160),\n"
                + "  method varchar(8),\n"
                + "  started_at timestamptz,\n"
                + "  ended_at timestamptz,\n"
                + "  duration_ms bigint,\n"
                + "  http_status smallint,\n"
                + "  error_code varchar(40),\n"
                + "  target_kind varchar(32),\n"
                + "  target_key uuid,\n"
                + "  screen_code varchar(64),\n"
                + "  from_screen_code varchar(64),\n"
                + "  client_at timestamptz,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_access_history PRIMARY KEY (id),\n"
                + "  CONSTRAINT ck_access_history_kind CHECK (kind IN ('SERVER','NAV') AND"
                + " actor_kind IN ('ADMIN','MEMBER','ANONYMOUS')),\n"
                + "  CONSTRAINT ck_access_history_actor CHECK ((actor_kind='ANONYMOUS' AND"
                + " actor_key IS NULL) OR (actor_kind IN ('ADMIN','MEMBER') AND actor_key IS NOT"
                + " NULL)),\n"
                + "  CONSTRAINT ck_access_history_status CHECK ((http_status IS NULL OR"
                + " http_status BETWEEN 100 AND 599) AND (duration_ms IS NULL OR"
                + " duration_ms>=0)),\n"
                + "  CONSTRAINT ck_access_history_shape CHECK ((kind='SERVER' AND route IS NOT"
                + " NULL AND method IS NOT NULL AND started_at IS NOT NULL AND event_key IS NULL"
                + " AND screen_code IS NULL AND from_screen_code IS NULL AND client_at IS NULL)"
                + " OR (kind='NAV' AND actor_kind<>'ANONYMOUS' AND event_key IS NOT NULL AND"
                + " screen_code IS NOT NULL AND http_status IS NULL AND duration_ms IS NULL AND"
                + " route IS NULL AND method IS NULL AND started_at IS NULL AND ended_at IS NULL"
                + " AND error_code IS NULL AND target_kind IS NULL AND target_key IS NULL)),\n"
                + "  CONSTRAINT ck_access_history_target CHECK ((target_kind IS"
                + " NULL)=(target_key IS NULL))\n"
                + ");\n"
                + "CREATE UNIQUE INDEX uq_access_history_request ON public.access_history"
                + " (request_id) WHERE kind='SERVER';\n"
                + "CREATE UNIQUE INDEX uq_access_history_event ON public.access_history"
                + " (actor_kind, actor_key, event_key) WHERE kind='NAV';\n"
                + "CREATE INDEX ix_access_history_actor ON public.access_history (actor_kind,"
                + " actor_key, id DESC);\n";
    }

    /** V2 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql2() {
        return "-- AUTH-ADMIN-01 relationship impact prerequisites. No H1 API or incident data is"
                + " installed here.\n"
                + "-- All five tables are created before their circular, approved FK constraints"
                + " are attached.\n"
                + "CREATE TABLE public.story (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  code varchar(40) NOT NULL,\n"
                + "  owner_id bigint NOT NULL,\n"
                + "  published_id bigint,\n"
                + "  view_yn boolean DEFAULT false NOT NULL,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  edit_rev bigint DEFAULT 0 NOT NULL,\n"
                + "  play_rev bigint DEFAULT 0 NOT NULL,\n"
                + "  CONSTRAINT pk_story PRIMARY KEY (id),\n"
                + "  CONSTRAINT uq_story_code UNIQUE (code),\n"
                + "  CONSTRAINT ck_story_code CHECK (code ~ '^[A-Z0-9_]{1,40}$'),\n"
                + "  CONSTRAINT ck_story_visible CHECK (NOT view_yn OR (active_yn AND"
                + " published_id IS NOT NULL)),\n"
                + "  CONSTRAINT ck_story_revision CHECK (edit_rev >= 0),\n"
                + "  CONSTRAINT ck_story_play_rev CHECK (play_rev >= 0)\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.story_version (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  story_id bigint NOT NULL,\n"
                + "  version_no integer NOT NULL,\n"
                + "  edit_rev bigint DEFAULT 0 NOT NULL,\n"
                + "  status varchar(12) DEFAULT 'DRAFT' NOT NULL,\n"
                + "  title varchar(160) NOT NULL,\n"
                + "  intro text,\n"
                + "  setting text,\n"
                + "  difficulty smallint,\n"
                + "  est_min smallint,\n"
                + "  est_max smallint,\n"
                + "  limit_sec integer,\n"
                + "  policy_code varchar(40) NOT NULL,\n"
                + "  culprit_code varchar(32),\n"
                + "  method_answer text,\n"
                + "  time_answer text,\n"
                + "  motive_answer text,\n"
                + "  timeline_origin varchar(120),\n"
                + "  reveal_text text,\n"
                + "  current_snapshot_id bigint,\n"
                + "  created_by bigint NOT NULL,\n"
                + "  updated_by bigint NOT NULL,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  source_snapshot_id bigint,\n"
                + "  CONSTRAINT pk_story_version PRIMARY KEY (id),\n"
                + "  CONSTRAINT uq_story_version_number UNIQUE (story_id, version_no),\n"
                + "  CONSTRAINT uq_story_version_story_id UNIQUE (story_id, id),\n"
                + "  CONSTRAINT ck_story_version_status CHECK (status IN"
                + " ('DRAFT','REVIEW','READY','PUBLISHED')),\n"
                + "  CONSTRAINT ck_story_version_revision CHECK (version_no > 0 AND edit_rev >="
                + " 0),\n"
                + "  CONSTRAINT ck_story_version_title CHECK (char_length(btrim(title)) > 0),\n"
                + "  CONSTRAINT ck_story_version_difficulty CHECK (difficulty BETWEEN 1 AND"
                + " 5),\n"
                + "  CONSTRAINT ck_story_version_time CHECK ((est_min IS NULL OR est_min > 0)"
                + " AND (est_max IS NULL OR est_max > 0) AND (est_min IS NULL OR est_max IS NULL"
                + " OR est_max >= est_min) AND (limit_sec IS NULL OR limit_sec > 0)),\n"
                + "  CONSTRAINT ck_story_version_snapshot CHECK ((status='DRAFT' AND"
                + " current_snapshot_id IS NULL) OR (status<>'DRAFT' AND current_snapshot_id IS"
                + " NOT NULL)),\n"
                + "  CONSTRAINT ck_story_version_intro_len CHECK (char_length(intro) <="
                + " 12000),\n"
                + "  CONSTRAINT ck_story_version_setting_len CHECK (char_length(setting) <="
                + " 4000),\n"
                + "  CONSTRAINT ck_story_version_method_answer_len CHECK"
                + " (char_length(method_answer) <= 12000),\n"
                + "  CONSTRAINT ck_story_version_time_answer_len CHECK (char_length(time_answer)"
                + " <= 8000),\n"
                + "  CONSTRAINT ck_story_version_motive_answer_len CHECK"
                + " (char_length(motive_answer) <= 8000),\n"
                + "  CONSTRAINT ck_story_version_reveal_text_len CHECK (char_length(reveal_text)"
                + " <= 20000)\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.story_person (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  name varchar(80) NOT NULL,\n"
                + "  public_text text,\n"
                + "  secret_text text,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_person PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT ck_story_person_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_story_person_name CHECK (char_length(btrim(name)) > 0),\n"
                + "  CONSTRAINT ck_story_person_text CHECK (char_length(public_text)<=8000 AND"
                + " char_length(secret_text)<=8000)\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.review_snapshot (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  version_id bigint NOT NULL,\n"
                + "  edit_rev bigint NOT NULL,\n"
                + "  format_no smallint DEFAULT 1 NOT NULL,\n"
                + "  payload jsonb NOT NULL,\n"
                + "  request_key uuid NOT NULL,\n"
                + "  created_by bigint NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_review_snapshot PRIMARY KEY (id),\n"
                + "  CONSTRAINT uq_review_snapshot_version_id UNIQUE (version_id, id),\n"
                + "  CONSTRAINT uq_review_snapshot_request UNIQUE (version_id, request_key),\n"
                + "  CONSTRAINT ck_review_snapshot_payload CHECK (edit_rev>=0 AND format_no>0"
                + " AND jsonb_typeof(payload)='object')\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.story_access (\n"
                + "  story_id bigint NOT NULL,\n"
                + "  admin_id bigint NOT NULL,\n"
                + "  permission varchar(8) NOT NULL,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  granted_by bigint NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_access PRIMARY KEY (story_id, admin_id, permission),\n"
                + "  CONSTRAINT ck_story_access_permission CHECK (permission IN"
                + " ('EDIT','REVIEW','PUBLISH'))\n"
                + ");\n"
                + "\n"
                + "ALTER TABLE public.story ADD CONSTRAINT fk_story_owner FOREIGN KEY (owner_id)"
                + " REFERENCES public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION;\n"
                + "ALTER TABLE public.story ADD CONSTRAINT fk_story_published FOREIGN KEY (id,"
                + " published_id) REFERENCES public.story_version (story_id, id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_version ADD CONSTRAINT fk_story_version_created_by"
                + " FOREIGN KEY (created_by) REFERENCES public.admin_account (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_version ADD CONSTRAINT fk_story_version_culprit"
                + " FOREIGN KEY (id, culprit_code) REFERENCES public.story_person (version_id,"
                + " code) ON DELETE NO ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_version ADD CONSTRAINT fk_story_version_snapshot"
                + " FOREIGN KEY (id, current_snapshot_id) REFERENCES public.review_snapshot"
                + " (version_id, id) ON DELETE NO ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_version ADD CONSTRAINT fk_story_version_source"
                + " FOREIGN KEY (source_snapshot_id) REFERENCES public.review_snapshot (id) ON"
                + " DELETE NO ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_version ADD CONSTRAINT fk_story_version_story"
                + " FOREIGN KEY (story_id) REFERENCES public.story (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_version ADD CONSTRAINT fk_story_version_updated_by"
                + " FOREIGN KEY (updated_by) REFERENCES public.admin_account (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_person ADD CONSTRAINT fk_story_person_version"
                + " FOREIGN KEY (version_id) REFERENCES public.story_version (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.review_snapshot ADD CONSTRAINT fk_review_snapshot_creator"
                + " FOREIGN KEY (created_by) REFERENCES public.admin_account (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.review_snapshot ADD CONSTRAINT fk_review_snapshot_version"
                + " FOREIGN KEY (version_id) REFERENCES public.story_version (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_access ADD CONSTRAINT fk_story_access_admin_id"
                + " FOREIGN KEY (admin_id) REFERENCES public.admin_account (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_access ADD CONSTRAINT fk_story_access_granted_by"
                + " FOREIGN KEY (granted_by) REFERENCES public.admin_account (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_access ADD CONSTRAINT fk_story_access_story_id"
                + " FOREIGN KEY (story_id) REFERENCES public.story (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "\n"
                + "CREATE INDEX ix_story_owner ON public.story (owner_id, id DESC) WHERE"
                + " active_yn;\n"
                + "CREATE UNIQUE INDEX ux_story_version_work ON public.story_version (story_id)"
                + " WHERE status IN ('DRAFT','REVIEW','READY');\n"
                + "CREATE INDEX ix_story_access_admin ON public.story_access (admin_id,"
                + " story_id, permission) WHERE active_yn;\n"
                + "\n"
                + "COMMENT ON TABLE public.story IS '사건';\n"
                + "COMMENT ON COLUMN public.story.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.story.code IS '사건 고정 코드';\n"
                + "COMMENT ON COLUMN public.story.owner_id IS '소유 관리자';\n"
                + "COMMENT ON COLUMN public.story.published_id IS '신규 게임용 공개 버전';\n"
                + "COMMENT ON COLUMN public.story.view_yn IS '일반 목록 노출';\n"
                + "COMMENT ON COLUMN public.story.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story.updated_at IS '서버 수정 시각';\n"
                + "COMMENT ON COLUMN public.story.edit_rev IS '사건 상태·접근 설정 수정번호';\n"
                + "COMMENT ON COLUMN public.story.play_rev IS '신규 게임 진입 세대';\n"
                + "\n"
                + "COMMENT ON TABLE public.story_version IS '사건 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.story_version.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.story_version.story_id IS '소속 사건';\n"
                + "COMMENT ON COLUMN public.story_version.version_no IS '사건 내 버전 번호';\n"
                + "COMMENT ON COLUMN public.story_version.edit_rev IS '콘텐츠 및 검수 흐름 충돌 방지"
                + " 수정번호';\n"
                + "COMMENT ON COLUMN public.story_version.status IS '업무 상태';\n"
                + "COMMENT ON COLUMN public.story_version.title IS '사건 제목';\n"
                + "COMMENT ON COLUMN public.story_version.intro IS '플레이어 공통 도입';\n"
                + "COMMENT ON COLUMN public.story_version.setting IS '시대·장소 소개';\n"
                + "COMMENT ON COLUMN public.story_version.difficulty IS '목표 난이도';\n"
                + "COMMENT ON COLUMN public.story_version.est_min IS '예상 최소 시간(분)';\n"
                + "COMMENT ON COLUMN public.story_version.est_max IS '예상 최대 시간(분)';\n"
                + "COMMENT ON COLUMN public.story_version.limit_sec IS '최대 플레이 시간(초)';\n"
                + "COMMENT ON COLUMN public.story_version.policy_code IS '공통 정책 버전 식별자';\n"
                + "COMMENT ON COLUMN public.story_version.culprit_code IS '정답 범인 코드';\n"
                + "COMMENT ON COLUMN public.story_version.method_answer IS '정답 수법 설명';\n"
                + "COMMENT ON COLUMN public.story_version.time_answer IS '허용 시간 구간 설명';\n"
                + "COMMENT ON COLUMN public.story_version.motive_answer IS '정답 동기 설명';\n"
                + "COMMENT ON COLUMN public.story_version.timeline_origin IS '시간선 기준점';\n"
                + "COMMENT ON COLUMN public.story_version.reveal_text IS '종료 후 진실 재생·해설';\n"
                + "COMMENT ON COLUMN public.story_version.current_snapshot_id IS '현재 검수 회차';\n"
                + "COMMENT ON COLUMN public.story_version.created_by IS '생성자';\n"
                + "COMMENT ON COLUMN public.story_version.updated_by IS '마지막 수정자';\n"
                + "COMMENT ON COLUMN public.story_version.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_version.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_version.updated_at IS '서버 수정 시각';\n"
                + "COMMENT ON COLUMN public.story_version.source_snapshot_id IS '복제 원본 공개 사본';\n"
                + "\n"
                + "COMMENT ON TABLE public.story_person IS '사건 인물';\n"
                + "COMMENT ON COLUMN public.story_person.version_id IS '소속 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.story_person.code IS '버전 내 고정 코드';\n"
                + "COMMENT ON COLUMN public.story_person.name IS '인물명';\n"
                + "COMMENT ON COLUMN public.story_person.public_text IS '공개 소개·고정 진술';\n"
                + "COMMENT ON COLUMN public.story_person.secret_text IS '비밀·실제 행동';\n"
                + "COMMENT ON COLUMN public.story_person.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_person.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_person.updated_at IS '서버 수정 시각';\n"
                + "\n"
                + "COMMENT ON TABLE public.review_snapshot IS '검수 고정 사본';\n"
                + "COMMENT ON COLUMN public.review_snapshot.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.review_snapshot.version_id IS '대상 버전';\n"
                + "COMMENT ON COLUMN public.review_snapshot.edit_rev IS '고정한 수정번호';\n"
                + "COMMENT ON COLUMN public.review_snapshot.format_no IS 'payload 형식 버전';\n"
                + "COMMENT ON COLUMN public.review_snapshot.payload IS '전체 콘텐츠·관계·적용 공통정책 값';\n"
                + "COMMENT ON COLUMN public.review_snapshot.created_by IS '검수 요청자';\n"
                + "COMMENT ON COLUMN public.review_snapshot.created_at IS '생성 시각';\n"
                + "COMMENT ON COLUMN public.review_snapshot.request_key IS '검수 요청 중복 방지 키';\n"
                + "\n"
                + "COMMENT ON TABLE public.story_access IS '사건별 작업 권한';\n"
                + "COMMENT ON COLUMN public.story_access.story_id IS '대상 사건';\n"
                + "COMMENT ON COLUMN public.story_access.admin_id IS '대상 관리자';\n"
                + "COMMENT ON COLUMN public.story_access.permission IS '작업 권한';\n"
                + "COMMENT ON COLUMN public.story_access.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_access.granted_by IS '마지막 부여·회수 처리자';\n"
                + "COMMENT ON COLUMN public.story_access.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_access.updated_at IS '서버 수정 시각';\n";
    }

    /** V3 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql3() {
        return "-- H1 story audit only. IDX-030 remains an unapplied, unmeasured performance"
                + " candidate.\n"
                + "CREATE TABLE public.story_audit (\n"
                + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "  story_id bigint NOT NULL,\n"
                + "  version_id bigint,\n"
                + "  actor_id bigint,\n"
                + "  target_admin_id bigint,\n"
                + "  action varchar(32) NOT NULL,\n"
                + "  before_rev bigint,\n"
                + "  after_rev bigint,\n"
                + "  detail jsonb NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_audit PRIMARY KEY (id),\n"
                + "  CONSTRAINT ck_story_audit_detail CHECK (jsonb_typeof(detail)='object'),\n"
                + "  CONSTRAINT ck_story_audit_revision CHECK (before_rev>=0 AND"
                + " after_rev>=0),\n"
                + "  CONSTRAINT ck_sa_system CHECK (actor_id IS NOT NULL OR (action IN"
                + " ('OWNER_EXPIRED','OWNER_INVALIDATED') AND detail->>'actorKind' IS NOT"
                + " DISTINCT FROM 'SYSTEM'))\n"
                + ");\n"
                + "\n"
                + "ALTER TABLE public.story_audit ADD CONSTRAINT fk_story_audit_story FOREIGN"
                + " KEY (story_id) REFERENCES public.story (id) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION;\n"
                + "ALTER TABLE public.story_audit ADD CONSTRAINT fk_story_audit_version FOREIGN"
                + " KEY (story_id, version_id) REFERENCES public.story_version (story_id, id) ON"
                + " DELETE NO ACTION ON UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_audit ADD CONSTRAINT fk_story_audit_actor FOREIGN"
                + " KEY (actor_id) REFERENCES public.admin_account (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "ALTER TABLE public.story_audit ADD CONSTRAINT fk_story_audit_target FOREIGN"
                + " KEY (target_admin_id) REFERENCES public.admin_account (id) ON DELETE NO"
                + " ACTION ON UPDATE NO ACTION;\n"
                + "\n"
                + "COMMENT ON TABLE public.story_audit IS '사건 변경 이력';\n"
                + "COMMENT ON COLUMN public.story_audit.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.story_audit.story_id IS '대상 사건';\n"
                + "COMMENT ON COLUMN public.story_audit.version_id IS '대상 버전';\n"
                + "COMMENT ON COLUMN public.story_audit.actor_id IS '행위 관리자';\n"
                + "COMMENT ON COLUMN public.story_audit.target_admin_id IS '권한 변경 대상';\n"
                + "COMMENT ON COLUMN public.story_audit.action IS '서버 정의 행동 코드';\n"
                + "COMMENT ON COLUMN public.story_audit.before_rev IS '이전 수정번호';\n"
                + "COMMENT ON COLUMN public.story_audit.after_rev IS '이후 수정번호';\n"
                + "COMMENT ON COLUMN public.story_audit.detail IS '요청 ID·수정 범위·허용 필드명; 원고·정답·전후"
                + " 값 제외';\n"
                + "COMMENT ON COLUMN public.story_audit.created_at IS '변경 확정 시각';\n"
                + "-- H1 사건 감사만 생성한다. IDX-030은 미적용·미측정 성능 후보다.\n";
    }

    /** V4 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql4() {
        return "CREATE TABLE public.story_role (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  name varchar(80) NOT NULL,\n"
                + "  brief text,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_role PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT ck_story_role_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_story_role_text CHECK (char_length(btrim(name)) > 0 AND"
                + " char_length(brief) <= 4000),\n"
                + "  CONSTRAINT fk_story_role_version FOREIGN KEY (version_id) REFERENCES"
                + " public.story_version (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.story_pair (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  role_a varchar(32) NOT NULL,\n"
                + "  role_b varchar(32) NOT NULL,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_pair PRIMARY KEY (version_id, role_a, role_b),\n"
                + "  CONSTRAINT ck_story_pair_order CHECK (role_a COLLATE \"C\" < role_b COLLATE"
                + " \"C\"),\n"
                + "  CONSTRAINT fk_story_pair_role_a FOREIGN KEY (version_id, role_a) REFERENCES"
                + " public.story_role (version_id, code) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION,\n"
                + "  CONSTRAINT fk_story_pair_role_b FOREIGN KEY (version_id, role_b) REFERENCES"
                + " public.story_role (version_id, code) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.story_role IS '사건 버전의 배정 역할';\n"
                + "COMMENT ON COLUMN public.story_role.version_id IS '소속 사건 버전';\n"
                + "COMMENT ON COLUMN public.story_role.code IS '변경 불가 역할 코드';\n"
                + "COMMENT ON COLUMN public.story_role.name IS '역할 이름';\n"
                + "COMMENT ON COLUMN public.story_role.brief IS '역할 소개 원고';\n"
                + "COMMENT ON COLUMN public.story_role.active_yn IS '역할 활성 여부';\n"
                + "COMMENT ON COLUMN public.story_role.created_at IS '생성 시각';\n"
                + "COMMENT ON COLUMN public.story_role.updated_at IS '수정 시각';\n"
                + "COMMENT ON TABLE public.story_pair IS '동일 버전 역할 사이의 조합';\n"
                + "COMMENT ON COLUMN public.story_pair.version_id IS '소속 사건 버전';\n"
                + "COMMENT ON COLUMN public.story_pair.role_a IS 'ASCII 순서상 앞선 역할 코드';\n"
                + "COMMENT ON COLUMN public.story_pair.role_b IS 'ASCII 순서상 뒤따르는 역할 코드';\n"
                + "COMMENT ON COLUMN public.story_pair.active_yn IS '조합 활성 여부';\n"
                + "COMMENT ON COLUMN public.story_pair.created_at IS '생성 시각';\n"
                + "COMMENT ON COLUMN public.story_pair.updated_at IS '수정 시각';\n";
    }

    /** V5 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql5() {
        return "CREATE TABLE public.story_clue (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  title varchar(160) NOT NULL,\n"
                + "  body text,\n"
                + "  person_code varchar(32),\n"
                + "  scope varchar(8) DEFAULT 'ROLE' NOT NULL,\n"
                + "  source_text varchar(400),\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_clue PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT ck_story_clue_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_story_clue_scope CHECK (scope IN ('COMMON','ROLE')),\n"
                + "  CONSTRAINT ck_story_clue_text CHECK (char_length(btrim(title)) > 0 AND"
                + " char_length(body) <= 12000),\n"
                + "  CONSTRAINT fk_story_clue_version FOREIGN KEY (version_id) REFERENCES"
                + " public.story_version (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "  CONSTRAINT fk_story_clue_person FOREIGN KEY (version_id, person_code)"
                + " REFERENCES public.story_person (version_id, code) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.clue_role (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  clue_code varchar(32) NOT NULL,\n"
                + "  role_code varchar(32) NOT NULL,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_clue_role PRIMARY KEY (version_id, clue_code, role_code),\n"
                + "  CONSTRAINT fk_clue_role_clue FOREIGN KEY (version_id, clue_code) REFERENCES"
                + " public.story_clue (version_id, code) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION,\n"
                + "  CONSTRAINT fk_clue_role_role FOREIGN KEY (version_id, role_code) REFERENCES"
                + " public.story_role (version_id, code) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION\n"
                + ");\n"
                + "\n"
                + "CREATE INDEX ix_clue_role_role ON public.clue_role (version_id, role_code,"
                + " clue_code) WHERE active_yn;\n"
                + "\n"
                + "COMMENT ON TABLE public.story_clue IS '사건 단서';\n"
                + "COMMENT ON COLUMN public.story_clue.version_id IS '소속 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.story_clue.code IS '버전 내 고정 코드';\n"
                + "COMMENT ON COLUMN public.story_clue.title IS '단서 제목';\n"
                + "COMMENT ON COLUMN public.story_clue.body IS '플레이어 본문';\n"
                + "COMMENT ON COLUMN public.story_clue.person_code IS '주요 관련 인물';\n"
                + "COMMENT ON COLUMN public.story_clue.scope IS '노출 대상';\n"
                + "COMMENT ON COLUMN public.story_clue.source_text IS '자료 출처·관찰 조건';\n"
                + "COMMENT ON COLUMN public.story_clue.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_clue.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_clue.updated_at IS '서버 수정 시각';\n"
                + "COMMENT ON TABLE public.clue_role IS '단서 역할 배정';\n"
                + "COMMENT ON COLUMN public.clue_role.version_id IS '소속 버전';\n"
                + "COMMENT ON COLUMN public.clue_role.clue_code IS '단서 코드';\n"
                + "COMMENT ON COLUMN public.clue_role.role_code IS '열람 역할 코드';\n"
                + "COMMENT ON COLUMN public.clue_role.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.clue_role.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.clue_role.updated_at IS '서버 수정 시각';\n";
    }

    /** V6 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql6() {
        return "CREATE TABLE public.story_hint (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  level smallint NOT NULL,\n"
                + "  body text,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_hint PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT uq_story_hint_level UNIQUE (version_id, level),\n"
                + "  CONSTRAINT ck_story_hint_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_story_hint_content CHECK (level BETWEEN 1 AND 3 AND"
                + " char_length(body) <= 4000),\n"
                + "  CONSTRAINT fk_story_hint_version FOREIGN KEY (version_id) REFERENCES"
                + " public.story_version (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.story_hint IS '사건 힌트';\n"
                + "COMMENT ON COLUMN public.story_hint.version_id IS '소속 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.story_hint.code IS '버전 내 고정 코드';\n"
                + "COMMENT ON COLUMN public.story_hint.level IS '관찰1·연결2·추론3';\n"
                + "COMMENT ON COLUMN public.story_hint.body IS '힌트 원고';\n"
                + "COMMENT ON COLUMN public.story_hint.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_hint.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_hint.updated_at IS '서버 수정 시각';\n";
    }

    /** V7 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql7() {
        return "CREATE TABLE public.story_event (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  start_min integer,\n"
                + "  end_min integer,\n"
                + "  actual_text text,\n"
                + "  apparent_text text,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_event PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT ck_story_event_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_story_event_time CHECK (start_min >= 0 AND end_min >="
                + " start_min),\n"
                + "  CONSTRAINT ck_story_event_bound CHECK (end_min IS NULL OR start_min IS NOT"
                + " NULL),\n"
                + "  CONSTRAINT ck_story_event_text CHECK (char_length(actual_text)<=8000 AND"
                + " char_length(apparent_text)<=8000),\n"
                + "  CONSTRAINT fk_story_event_version FOREIGN KEY (version_id) REFERENCES"
                + " public.story_version (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.story_event IS '사건 시간선';\n"
                + "COMMENT ON COLUMN public.story_event.version_id IS '소속 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.story_event.code IS '버전 내 고정 코드';\n"
                + "COMMENT ON COLUMN public.story_event.start_min IS '기준점 이후 시작 분';\n"
                + "COMMENT ON COLUMN public.story_event.end_min IS '기준점 이후 끝 분';\n"
                + "COMMENT ON COLUMN public.story_event.actual_text IS '실제 사건';\n"
                + "COMMENT ON COLUMN public.story_event.apparent_text IS '표면 사건·오해';\n"
                + "COMMENT ON COLUMN public.story_event.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_event.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_event.updated_at IS '서버 수정 시각';\n";
    }

    /** V8 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql8() {
        return "CREATE TABLE public.story_fact (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  statement text,\n"
                + "  truth varchar(12),\n"
                + "  basis text,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_story_fact PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT ck_story_fact_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_story_fact_truth CHECK (truth IN"
                + " ('TRUE','FALSE','MISREAD')),\n"
                + "  CONSTRAINT ck_story_fact_text CHECK (char_length(statement)<=4000 AND"
                + " char_length(basis)<=8000),\n"
                + "  CONSTRAINT fk_story_fact_version FOREIGN KEY (version_id) REFERENCES"
                + " public.story_version (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.story_fact IS '사실 원장';\n"
                + "COMMENT ON COLUMN public.story_fact.version_id IS '소속 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.story_fact.code IS '버전 내 고정 코드';\n"
                + "COMMENT ON COLUMN public.story_fact.statement IS '명제';\n"
                + "COMMENT ON COLUMN public.story_fact.truth IS '분류';\n"
                + "COMMENT ON COLUMN public.story_fact.basis IS '근거 단서 코드·연결 설명';\n"
                + "COMMENT ON COLUMN public.story_fact.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_fact.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_fact.updated_at IS '서버 수정 시각';\n";
    }

    /** V9 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql9() {
        return "CREATE TABLE public.story_rubric (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  category varchar(12) NOT NULL,\n"
                + "  max_score smallint,\n"
                + "  required_yn boolean DEFAULT false NOT NULL,\n"
                + "  pass_score smallint,\n"
                + "  accepted_text text,\n"
                + "  partial_text text,\n"
                + "  reject_text text,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  rule_data jsonb,\n"
                + "  CONSTRAINT pk_story_rubric PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT ck_story_rubric_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_story_rubric_category CHECK (category IN"
                + " ('CULPRIT','METHOD','TIME','MOTIVE','EVIDENCE')),\n"
                + "  CONSTRAINT ck_story_rubric_score CHECK (max_score BETWEEN 0 AND 100 AND"
                + " pass_score BETWEEN 0 AND max_score),\n"
                + "  CONSTRAINT ck_story_rubric_required CHECK (pass_score IS NULL OR"
                + " (required_yn AND max_score IS NOT NULL)),\n"
                + "  CONSTRAINT ck_story_rubric_text CHECK (char_length(accepted_text)<=12000"
                + " AND char_length(partial_text)<=12000 AND char_length(reject_text)<=8000),\n"
                + "  CONSTRAINT ck_story_rubric_rule CHECK (rule_data IS NULL OR"
                + " (jsonb_typeof(rule_data)='object' AND"
                + " octet_length(rule_data::text)<=131072)),\n"
                + "  CONSTRAINT fk_story_rubric_version FOREIGN KEY (version_id) REFERENCES"
                + " public.story_version (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.rubric_clue (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  rubric_code varchar(32) NOT NULL,\n"
                + "  clue_code varchar(32) NOT NULL,\n"
                + "  link_text text,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_rubric_clue PRIMARY KEY (version_id, rubric_code,"
                + " clue_code),\n"
                + "  CONSTRAINT ck_rubric_clue_text CHECK (char_length(link_text)<=4000),\n"
                + "  CONSTRAINT fk_rubric_clue_rubric FOREIGN KEY (version_id, rubric_code)"
                + " REFERENCES public.story_rubric (version_id, code) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION,\n"
                + "  CONSTRAINT fk_rubric_clue_clue FOREIGN KEY (version_id, clue_code)"
                + " REFERENCES public.story_clue (version_id, code) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.story_rubric IS '채점 소항목';\n"
                + "COMMENT ON COLUMN public.story_rubric.version_id IS '소속 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.story_rubric.code IS '버전 내 고정 코드';\n"
                + "COMMENT ON COLUMN public.story_rubric.category IS '공통 상위 항목';\n"
                + "COMMENT ON COLUMN public.story_rubric.max_score IS '소항목 만점';\n"
                + "COMMENT ON COLUMN public.story_rubric.required_yn IS '성공 필수 여부';\n"
                + "COMMENT ON COLUMN public.story_rubric.pass_score IS '필수 조건 충족 최소 소항목 점수';\n"
                + "COMMENT ON COLUMN public.story_rubric.accepted_text IS '허용 답·동등 표현';\n"
                + "COMMENT ON COLUMN public.story_rubric.partial_text IS '부분 점수 조건';\n"
                + "COMMENT ON COLUMN public.story_rubric.reject_text IS '모순·불인정 조건';\n"
                + "COMMENT ON COLUMN public.story_rubric.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.story_rubric.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.story_rubric.updated_at IS '서버 수정 시각';\n"
                + "COMMENT ON COLUMN public.story_rubric.rule_data IS '사건별 구조화 명제·점수 단계';\n"
                + "COMMENT ON TABLE public.rubric_clue IS '채점 근거 연결';\n"
                + "COMMENT ON COLUMN public.rubric_clue.version_id IS '소속 버전';\n"
                + "COMMENT ON COLUMN public.rubric_clue.rubric_code IS '채점 소항목 코드';\n"
                + "COMMENT ON COLUMN public.rubric_clue.clue_code IS '근거 단서 코드';\n"
                + "COMMENT ON COLUMN public.rubric_clue.link_text IS '이 단서가 뒷받침하는 논리';\n"
                + "COMMENT ON COLUMN public.rubric_clue.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.rubric_clue.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.rubric_clue.updated_at IS '서버 수정 시각';\n";
    }

    /** V10 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql10() {
        return "CREATE TABLE public.grade_sample (\n"
                + "  version_id bigint NOT NULL,\n"
                + "  code varchar(32) NOT NULL,\n"
                + "  input_data jsonb,\n"
                + "  expect_data jsonb,\n"
                + "  expected_score smallint,\n"
                + "  expected_success boolean,\n"
                + "  reason text,\n"
                + "  checked_by bigint,\n"
                + "  active_yn boolean DEFAULT true NOT NULL,\n"
                + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "  CONSTRAINT pk_grade_sample PRIMARY KEY (version_id, code),\n"
                + "  CONSTRAINT ck_grade_sample_code CHECK (code ~ '^[A-Z0-9_]{1,32}$'),\n"
                + "  CONSTRAINT ck_grade_sample_score CHECK (expected_score BETWEEN 0 AND"
                + " 100),\n"
                + "  CONSTRAINT ck_grade_sample_text CHECK (char_length(reason)<=8000),\n"
                + "  CONSTRAINT ck_grade_sample_json CHECK (\n"
                + "    (input_data IS NULL OR (jsonb_typeof(input_data)='object' AND"
                + " octet_length(input_data::text)<=131072))\n"
                + "    AND (expect_data IS NULL OR (jsonb_typeof(expect_data)='object' AND"
                + " octet_length(expect_data::text)<=65536))\n"
                + "  ),\n"
                + "  CONSTRAINT fk_grade_sample_version FOREIGN KEY (version_id) REFERENCES"
                + " public.story_version (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "  CONSTRAINT fk_grade_sample_checker FOREIGN KEY (checked_by) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.grade_sample IS '판정 기준 예시';\n"
                + "COMMENT ON COLUMN public.grade_sample.version_id IS '소속 콘텐츠 버전';\n"
                + "COMMENT ON COLUMN public.grade_sample.code IS '버전 내 고정 코드';\n"
                + "COMMENT ON COLUMN public.grade_sample.input_data IS '전체 제출 입력 및 합성 장애 주입';\n"
                + "COMMENT ON COLUMN public.grade_sample.expect_data IS '종류별 항목 기대값 및 오류 기대"
                + " 상태';\n"
                + "COMMENT ON COLUMN public.grade_sample.expected_score IS '감점 전 기대 점수';\n"
                + "COMMENT ON COLUMN public.grade_sample.expected_success IS '기대 성공 여부';\n"
                + "COMMENT ON COLUMN public.grade_sample.reason IS '기대값 근거';\n"
                + "COMMENT ON COLUMN public.grade_sample.checked_by IS '사람 검수자';\n"
                + "COMMENT ON COLUMN public.grade_sample.active_yn IS '논리 사용 상태';\n"
                + "COMMENT ON COLUMN public.grade_sample.created_at IS '서버 생성 시각';\n"
                + "COMMENT ON COLUMN public.grade_sample.updated_at IS '서버 수정 시각';\n";
    }

    /** V11 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql11() {
        return "-- STORY-OWNER-01: existing approved story_action receipt and story_transfer"
                   + " lifecycle.\n"
                   + "-- The existing ck_sa_system already restricts NULL story_audit actors to"
                   + " owner maintenance events.\n"
                   + "CREATE TABLE public.story_action (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  story_id bigint NOT NULL,\n"
                   + "  request_key uuid NOT NULL,\n"
                   + "  action varchar(12) NOT NULL,\n"
                   + "  actor_id bigint NOT NULL,\n"
                   + "  request_data jsonb NOT NULL,\n"
                   + "  result_data jsonb NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  CONSTRAINT pk_story_action PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uq_story_action_request UNIQUE (story_id, request_key),\n"
                   + "  CONSTRAINT ck_story_action_kind CHECK (action IN"
                   + " ('READY','PUBLISH','SHOW','HIDE','CLONE','OWNER_REQ','OWNER_ACCEPT','OWNER_CLOSE','OWNER_FORCE')),\n"
                   + "  CONSTRAINT ck_story_action_request CHECK"
                   + " (jsonb_typeof(request_data)='object' AND"
                   + " octet_length(request_data::text)<=8192),\n"
                   + "  CONSTRAINT ck_story_action_result CHECK (jsonb_typeof(result_data)='object'"
                   + " AND octet_length(result_data::text)<=32768),\n"
                   + "  CONSTRAINT fk_story_action_story FOREIGN KEY (story_id) REFERENCES"
                   + " public.story (id),\n"
                   + "  CONSTRAINT fk_story_action_actor FOREIGN KEY (actor_id) REFERENCES"
                   + " public.admin_account (id)\n"
                   + ");\n"
                   + "\n"
                   + "CREATE TABLE public.story_transfer (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  transfer_key uuid NOT NULL,\n"
                   + "  story_id bigint NOT NULL,\n"
                   + "  from_id bigint NOT NULL,\n"
                   + "  to_id bigint NOT NULL,\n"
                   + "  actor_id bigint NOT NULL,\n"
                   + "  mode varchar(8) NOT NULL,\n"
                   + "  state varchar(16) NOT NULL,\n"
                   + "  keep_editor boolean NOT NULL,\n"
                   + "  story_rev bigint NOT NULL,\n"
                   + "  from_auth_rev bigint NOT NULL,\n"
                   + "  to_auth_rev bigint NOT NULL,\n"
                   + "  reason_code varchar(32) NOT NULL,\n"
                   + "  verification_ref varchar(64) NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  expires_at timestamptz,\n"
                   + "  closed_at timestamptz,\n"
                   + "  closed_by bigint,\n"
                   + "  CONSTRAINT pk_story_transfer PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_stf_key UNIQUE (transfer_key),\n"
                   + "  CONSTRAINT ck_stf_pair CHECK (from_id<>to_id),\n"
                   + "  CONSTRAINT ck_stf_rev CHECK (story_rev>=0 AND from_auth_rev>=0 AND"
                   + " to_auth_rev>=0),\n"
                   + "  CONSTRAINT ck_stf_ref CHECK (verification_ref ~ '^[A-Za-z0-9_-]{8,64}$'),\n"
                   + "  CONSTRAINT ck_stf_mode CHECK ((mode='NORMAL' AND actor_id=from_id AND state"
                   + " IN ('PENDING','ACCEPTED','CANCELLED','DECLINED','EXPIRED','INVALIDATED') AND"
                   + " reason_code='HANDOVER' AND expires_at IS NOT NULL AND"
                   + " expires_at=created_at+interval '24 hours') OR (mode='OVERRIDE' AND"
                   + " state='OVERRIDDEN' AND reason_code IN ('OWNER_DISABLED','OWNER_RECOVERY')"
                   + " AND expires_at IS NULL AND closed_at=created_at AND closed_by=actor_id)),\n"
                   + "  CONSTRAINT ck_stf_close CHECK ((state='PENDING' AND closed_at IS NULL AND"
                   + " closed_by IS NULL) OR (state<>'PENDING' AND closed_at IS NOT NULL AND"
                   + " closed_at>=created_at AND (state='INVALIDATED' OR (state='EXPIRED' AND"
                   + " closed_by IS NULL) OR (state IN"
                   + " ('ACCEPTED','CANCELLED','DECLINED','OVERRIDDEN') AND closed_by IS NOT"
                   + " NULL)))),\n"
                   + "  CONSTRAINT fk_stf_story_id FOREIGN KEY (story_id) REFERENCES public.story"
                   + " (id),\n"
                   + "  CONSTRAINT fk_stf_from_id FOREIGN KEY (from_id) REFERENCES"
                   + " public.admin_account (id),\n"
                   + "  CONSTRAINT fk_stf_to_id FOREIGN KEY (to_id) REFERENCES public.admin_account"
                   + " (id),\n"
                   + "  CONSTRAINT fk_stf_actor_id FOREIGN KEY (actor_id) REFERENCES"
                   + " public.admin_account (id),\n"
                   + "  CONSTRAINT fk_stf_closed_by FOREIGN KEY (closed_by) REFERENCES"
                   + " public.admin_account (id)\n"
                   + ");\n"
                   + "\n"
                   + "CREATE UNIQUE INDEX uk_stf_pending ON public.story_transfer (story_id) WHERE"
                   + " state='PENDING';\n"
                   + "CREATE INDEX ix_stf_expiry ON public.story_transfer (expires_at, id) WHERE"
                   + " state='PENDING';\n"
                   + "\n"
                   + "COMMENT ON TABLE public.story_action IS '사건 공개·소유권 행위 영수증';\n"
                   + "COMMENT ON TABLE public.story_transfer IS '사건 소유권 인계';\n";
    }

    /** V12 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql12() {
        return "-- 사전은 의미 주석만 제공하며 별칭 발견으로 명제 충족이나 점수를 계산하지 않는다.\n"
                + "CREATE TABLE public.grade_term (\n"
                + "    dictionary_code varchar(80) NOT NULL,\n"
                + "    concept_code varchar(32) NOT NULL,\n"
                + "    canonical_text varchar(200) NOT NULL,\n"
                + "    alias_text varchar(200) NOT NULL,\n"
                + "    active_yn boolean DEFAULT true NOT NULL,\n"
                + "    CONSTRAINT pk_grade_term PRIMARY KEY (dictionary_code, concept_code,"
                + " alias_text),\n"
                + "    CONSTRAINT ck_grade_term_dictionary_code CHECK (dictionary_code ~"
                + " '^[A-Z0-9_]{1,80}$'),\n"
                + "    CONSTRAINT ck_grade_term_concept_code CHECK (concept_code ~"
                + " '^[A-Z0-9_]{1,32}$'),\n"
                + "    CONSTRAINT ck_grade_term_canonical_text CHECK (canonical_text ~"
                + " '[^[:space:]]'),\n"
                + "    CONSTRAINT ck_grade_term_alias_text CHECK (alias_text ~ '[^[:space:]]')\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.grade_term IS '활성 의미 주석 사전. 같은 별칭의 여러 개념을 보존한다. 같은"
                + " 개념의 표준어 일관성은 DB 보장이 아니라 애플리케이션 전체 사본 검사로 보장한다.';\n"
                + "COMMENT ON COLUMN public.grade_term.dictionary_code IS '등록된 사전 코드. 해시는 동일 활성"
                + " 행 사본에서 계산한다.';\n"
                + "COMMENT ON COLUMN public.grade_term.concept_code IS '의미 개념 코드. 별칭만으로 자동 충족"
                + " 판정하지 않는다.';\n"
                + "COMMENT ON COLUMN public.grade_term.canonical_text IS '표준 표현. 애플리케이션에서 LF"
                + " 정규화와 개념별 일관성을 검사한다.';\n"
                + "COMMENT ON COLUMN public.grade_term.alias_text IS '표현 별칭. 여러 개념의 동일 별칭을 모두"
                + " 보존하며 원문을 치환하지 않는다.';\n"
                + "COMMENT ON COLUMN public.grade_term.active_yn IS '활성 행 여부. 등록 해시와 다른 활성 사본은"
                + " 사용을 거절한다.';\n";
    }

    /** V13 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql13() {
        return "-- H2 회귀 전용 단계: TEST/GAME 출처와 회원 수집은 해당 기능의 후속 마이그레이션으로 추가한다.\n"
                + "-- 일반 DB 미적용. 시간 경과·상태 전환·사본 효력·해시 내용은 서비스의 트랜잭션 검사 책임이다.\n"
                + "CREATE TABLE public.grade_runtime (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    code varchar(80) NOT NULL,\n"
                + "    config_hash char(64) NOT NULL,\n"
                + "    config_data jsonb NOT NULL,\n"
                + "    state varchar(24) NOT NULL,\n"
                + "    epoch bigint DEFAULT 0 NOT NULL,\n"
                + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    CONSTRAINT pk_grade_runtime PRIMARY KEY (id),\n"
                + "    CONSTRAINT uk_grade_runtime_code UNIQUE (code),\n"
                + "    CONSTRAINT ck_runtime_state CHECK (state IN"
                + " ('AVAILABLE','SUSPENDED','RETIRED')),\n"
                + "    CONSTRAINT ck_runtime_epoch CHECK (epoch >= 0),\n"
                + "    CONSTRAINT ck_grade_runtime_config_hash CHECK (config_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_runtime_config_data CHECK (jsonb_typeof(config_data)"
                + " = 'object' AND octet_length(config_data::text) <= 131072)\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.grade_batch (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    batch_key uuid NOT NULL,\n"
                + "    snapshot_id bigint NOT NULL,\n"
                + "    runtime_id bigint NOT NULL,\n"
                + "    purpose varchar(24) NOT NULL,\n"
                + "    dataset_hash char(64) NOT NULL,\n"
                + "    rubric_hash char(64) NOT NULL,\n"
                + "    payload_hash char(64) NOT NULL,\n"
                + "    config_hash char(64) NOT NULL,\n"
                + "    runtime_epoch bigint NOT NULL,\n"
                + "    state varchar(24) NOT NULL,\n"
                + "    repeat_count smallint DEFAULT 3 NOT NULL,\n"
                + "    expected_count integer NOT NULL,\n"
                + "    passed_yn boolean,\n"
                + "    valid_until timestamptz,\n"
                + "    created_by bigint NOT NULL,\n"
                + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    ended_at timestamptz,\n"
                + "    CONSTRAINT pk_grade_batch PRIMARY KEY (id),\n"
                + "    CONSTRAINT uk_grade_batch_key UNIQUE (batch_key),\n"
                + "    CONSTRAINT uk_grade_batch_snapshot UNIQUE (snapshot_id, id),\n"
                + "    CONSTRAINT ck_gb_purpose CHECK (purpose IN ('REVIEW','AVAILABILITY')),\n"
                + "    CONSTRAINT ck_gb_state CHECK (state IN"
                + " ('STAGED','RUNNING','COMPLETED','FAILED','CANCELLED')),\n"
                + "    CONSTRAINT ck_gb_size CHECK (repeat_count = 3 AND expected_count > 0 AND"
                + " expected_count % 3 = 0 AND runtime_epoch >= 0),\n"
                + "    CONSTRAINT ck_gb_result CHECK ((state IN"
                + " ('COMPLETED','FAILED','CANCELLED')) = (ended_at IS NOT NULL) AND (passed_yn"
                + " IS NULL OR state = 'COMPLETED')),\n"
                + "    CONSTRAINT ck_grade_batch_dataset_hash CHECK (dataset_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_batch_rubric_hash CHECK (rubric_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_batch_payload_hash CHECK (payload_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_batch_config_hash CHECK (config_hash ~"
                + " '^[0-9a-f]{64}$')\n"
                + ");\n"
                + "ALTER TABLE public.grade_batch ADD CONSTRAINT fk_gb_snapshot FOREIGN KEY"
                + " (snapshot_id) REFERENCES public.review_snapshot (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "ALTER TABLE public.grade_batch ADD CONSTRAINT fk_gb_runtime FOREIGN KEY"
                + " (runtime_id) REFERENCES public.grade_runtime (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "ALTER TABLE public.grade_batch ADD CONSTRAINT fk_gb_admin FOREIGN KEY"
                + " (created_by) REFERENCES public.admin_account (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "\n"
                + "CREATE TABLE public.grade_job (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    job_key uuid NOT NULL,\n"
                + "    snapshot_id bigint NOT NULL,\n"
                + "    runtime_id bigint NOT NULL,\n"
                + "    batch_id bigint NOT NULL,\n"
                + "    sample_code varchar(32) NOT NULL,\n"
                + "    repeat_no smallint NOT NULL,\n"
                + "    state varchar(24) NOT NULL,\n"
                + "    accepted_at timestamptz,\n"
                + "    deadline_at timestamptz,\n"
                + "    call_count smallint DEFAULT 0 NOT NULL,\n"
                + "    lease_gen bigint DEFAULT 0 NOT NULL,\n"
                + "    lease_until timestamptz,\n"
                + "    worker_key varchar(80),\n"
                + "    next_run_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    input_hash char(64) NOT NULL,\n"
                + "    config_hash char(64) NOT NULL,\n"
                + "    rubric_hash char(64) NOT NULL,\n"
                + "    result_cipher bytea,\n"
                + "    result_data jsonb,\n"
                + "    result_hash char(64),\n"
                + "    error_code varchar(40),\n"
                + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    updated_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    CONSTRAINT pk_grade_job PRIMARY KEY (id),\n"
                + "    CONSTRAINT uk_grade_job_key UNIQUE (job_key),\n"
                + "    CONSTRAINT uk_grade_job_sample UNIQUE (batch_id, sample_code,"
                + " repeat_no),\n"
                + "    CONSTRAINT ck_gj_state CHECK (state IN"
                + " ('STAGED','QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),\n"
                + "    CONSTRAINT ck_gj_source CHECK (batch_id IS NOT NULL AND sample_code IS"
                + " NOT NULL AND repeat_no IS NOT NULL AND repeat_no BETWEEN 1 AND 3),\n"
                + "    CONSTRAINT ck_gj_running CHECK ((state = 'RUNNING') = (worker_key IS NOT"
                + " NULL)),\n"
                + "    CONSTRAINT ck_gj_budget CHECK (call_count BETWEEN 0 AND 3 AND lease_gen"
                + " >= 0),\n"
                + "    CONSTRAINT ck_gj_deadline CHECK ((accepted_at IS NULL AND deadline_at IS"
                + " NULL AND state IN ('STAGED','CANCELLED','FAILED')) OR (accepted_at IS NOT"
                + " NULL AND deadline_at IS NOT NULL AND deadline_at = accepted_at + interval"
                + " '120 seconds')),\n"
                + "    CONSTRAINT ck_gj_lease CHECK ((worker_key IS NULL) = (lease_until IS"
                + " NULL)),\n"
                + "    CONSTRAINT ck_grade_job_input_hash CHECK (input_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_job_config_hash CHECK (config_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_job_rubric_hash CHECK (rubric_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_job_result_cipher CHECK (octet_length(result_cipher)"
                + " BETWEEN 32 AND 524288),\n"
                + "    CONSTRAINT ck_grade_job_result_data CHECK (jsonb_typeof(result_data) ="
                + " 'object' AND octet_length(result_data::text) <= 131072),\n"
                + "    CONSTRAINT ck_grade_job_result_hash CHECK (result_hash ~"
                + " '^[0-9a-f]{64}$')\n"
                + ");\n"
                + "ALTER TABLE public.grade_job ADD CONSTRAINT fk_gj_snapshot FOREIGN KEY"
                + " (snapshot_id) REFERENCES public.review_snapshot (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "ALTER TABLE public.grade_job ADD CONSTRAINT fk_gj_runtime FOREIGN KEY"
                + " (runtime_id) REFERENCES public.grade_runtime (id) ON DELETE NO ACTION ON"
                + " UPDATE NO ACTION;\n"
                + "ALTER TABLE public.grade_job ADD CONSTRAINT fk_gj_batch FOREIGN KEY"
                + " (snapshot_id, batch_id) REFERENCES public.grade_batch (snapshot_id, id) ON"
                + " DELETE NO ACTION ON UPDATE NO ACTION;\n"
                + "CREATE UNIQUE INDEX uk_gj_worker ON public.grade_job (worker_key) WHERE state"
                + " = 'RUNNING' AND worker_key IS NOT NULL;\n"
                + "\n"
                + "CREATE TABLE public.grade_attempt (\n"
                + "    job_id bigint NOT NULL,\n"
                + "    attempt_no smallint NOT NULL,\n"
                + "    lease_gen bigint NOT NULL,\n"
                + "    worker_key varchar(80) NOT NULL,\n"
                + "    started_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    ended_at timestamptz,\n"
                + "    state varchar(24) NOT NULL,\n"
                + "    provider_ref varchar(120),\n"
                + "    error_code varchar(40),\n"
                + "    output_hash char(64),\n"
                + "    output_cipher bytea,\n"
                + "    observed_version varchar(160),\n"
                + "    completion_data jsonb,\n"
                + "    CONSTRAINT pk_grade_attempt PRIMARY KEY (job_id, attempt_no),\n"
                + "    CONSTRAINT uk_ga_lease UNIQUE (job_id, lease_gen),\n"
                + "    CONSTRAINT ck_ga_state CHECK (state IN"
                + " ('RUNNING','SUCCEEDED','FAILED','EXPIRED')),\n"
                + "    CONSTRAINT ck_ga_no CHECK (attempt_no BETWEEN 1 AND 3 AND lease_gen >"
                + " 0),\n"
                + "    CONSTRAINT ck_ga_end CHECK ((state <> 'RUNNING') = (ended_at IS NOT"
                + " NULL)),\n"
                + "    CONSTRAINT ck_grade_attempt_output_hash CHECK (output_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_attempt_output_cipher CHECK"
                + " (octet_length(output_cipher) BETWEEN 32 AND 524288),\n"
                + "    CONSTRAINT ck_grade_attempt_completion_data CHECK"
                + " (jsonb_typeof(completion_data) = 'object' AND"
                + " octet_length(completion_data::text) <= 131072)\n"
                + ");\n"
                + "ALTER TABLE public.grade_attempt ADD CONSTRAINT fk_ga_job FOREIGN KEY"
                + " (job_id) REFERENCES public.grade_job (id) ON DELETE NO ACTION ON UPDATE NO"
                + " ACTION;\n"
                + "\n"
                + "COMMENT ON TABLE public.grade_runtime IS '배포 경로로 등록한 불변 판정 실행 설정. AVAILABLE은"
                + " 모델 품질이나 GRADE 근거 승인 아님.';\n"
                + "COMMENT ON COLUMN public.grade_runtime.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.grade_runtime.code IS '공개 가능한 설정 코드. 구성 변경은 새 코드로"
                + " 등록';\n"
                + "COMMENT ON COLUMN public.grade_runtime.config_hash IS '비밀값 제외 실행 구성 해시. 형식"
                + " 검사는 신뢰된 구성 증명이 아님';\n"
                + "COMMENT ON COLUMN public.grade_runtime.config_data IS '엔진·모델·프롬프트·정책·포맷 고정"
                + " 구성. 키 원문 제외';\n"
                + "COMMENT ON COLUMN public.grade_runtime.state IS '운영 상태';\n"
                + "COMMENT ON COLUMN public.grade_runtime.epoch IS '가용성 변경 세대';\n"
                + "COMMENT ON COLUMN public.grade_runtime.created_at IS '생성 시각';\n"
                + "COMMENT ON COLUMN public.grade_runtime.updated_at IS '가용성 변경 시각';\n"
                + "COMMENT ON TABLE public.grade_batch IS '고정 회귀 실행 집합. 생성 후 24시간 상한은 서비스가"
                + " 검사';\n"
                + "COMMENT ON COLUMN public.grade_batch.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.grade_batch.batch_key IS '회귀 집합 식별자. 인증 수단 아님';\n"
                + "COMMENT ON COLUMN public.grade_batch.snapshot_id IS '고정 사본';\n"
                + "COMMENT ON COLUMN public.grade_batch.runtime_id IS '실행 설정';\n"
                + "COMMENT ON COLUMN public.grade_batch.purpose IS '검수 또는 운영 점검';\n"
                + "COMMENT ON COLUMN public.grade_batch.dataset_hash IS '전체 fixture 집합 해시';\n"
                + "COMMENT ON COLUMN public.grade_batch.rubric_hash IS '채점 명세 해시';\n"
                + "COMMENT ON COLUMN public.grade_batch.payload_hash IS '사본 해시';\n"
                + "COMMENT ON COLUMN public.grade_batch.config_hash IS '실행 구성 해시';\n"
                + "COMMENT ON COLUMN public.grade_batch.runtime_epoch IS '실행 예약 가용성 세대';\n"
                + "COMMENT ON COLUMN public.grade_batch.state IS '집합 실행 상태';\n"
                + "COMMENT ON COLUMN public.grade_batch.repeat_count IS '각 fixture 반복 수';\n"
                + "COMMENT ON COLUMN public.grade_batch.expected_count IS '모든 반복의 작업 수';\n"
                + "COMMENT ON COLUMN public.grade_batch.passed_yn IS '실제 전체 기대 비교 통과';\n"
                + "COMMENT ON COLUMN public.grade_batch.valid_until IS 'ALIAS UTC 당일 효력 상한';\n"
                + "COMMENT ON COLUMN public.grade_batch.created_by IS '실행 요청 관리자';\n"
                + "COMMENT ON COLUMN public.grade_batch.created_at IS '생성 시각';\n"
                + "COMMENT ON COLUMN public.grade_batch.ended_at IS '집합 완료 시각';\n"
                + "COMMENT ON TABLE public.grade_job IS '회귀 fixture 한 반복의 내구 판정 작업. TEST/GAME"
                + " 출처는 후속 기능 범위';\n"
                + "COMMENT ON COLUMN public.grade_job.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.grade_job.job_key IS '작업 API 식별자. 인증 수단 아님';\n"
                + "COMMENT ON COLUMN public.grade_job.snapshot_id IS '고정 사본. 회귀 집합과 같은 사본 참조';\n"
                + "COMMENT ON COLUMN public.grade_job.runtime_id IS '실행 설정. 집합 구성 일치는 서비스 검사';\n"
                + "COMMENT ON COLUMN public.grade_job.batch_id IS '필수 회귀 집합';\n"
                + "COMMENT ON COLUMN public.grade_job.sample_code IS '고정 사본 fixture 코드. 사본 목록"
                + " 대조는 서비스 검사';\n"
                + "COMMENT ON COLUMN public.grade_job.repeat_no IS 'fixture 반복 번호';\n"
                + "COMMENT ON COLUMN public.grade_job.state IS '판정 상태';\n"
                + "COMMENT ON COLUMN public.grade_job.accepted_at IS '서버 admission 후 120초 예산"
                + " 시작';\n"
                + "COMMENT ON COLUMN public.grade_job.deadline_at IS '고정 판정 마감';\n"
                + "COMMENT ON COLUMN public.grade_job.call_count IS '내구 예약된 외부 호출 수. 세 번째"
                + " RUNNING 호출도 수집 가능';\n"
                + "COMMENT ON COLUMN public.grade_job.lease_gen IS '작업 임대 세대';\n"
                + "COMMENT ON COLUMN public.grade_job.lease_until IS '임대 만료. 마감 이내 갱신은 서비스"
                + " 검사';\n"
                + "COMMENT ON COLUMN public.grade_job.worker_key IS '등록 실행기 식별자';\n"
                + "COMMENT ON COLUMN public.grade_job.next_run_at IS '다음 실행 가능 시각';\n"
                + "COMMENT ON COLUMN public.grade_job.input_hash IS '실제 fixture 입력 해시';\n"
                + "COMMENT ON COLUMN public.grade_job.config_hash IS '실행 설정 해시';\n"
                + "COMMENT ON COLUMN public.grade_job.rubric_hash IS '채점표 해시';\n"
                + "COMMENT ON COLUMN public.grade_job.result_cipher IS '최종 판정 근거 AES-256-GCM 봉투."
                + " AAD는 테이블·행·필드·포맷';\n"
                + "COMMENT ON COLUMN public.grade_job.result_data IS '점수·조건 또는 오류·기대 비교';\n"
                + "COMMENT ON COLUMN public.grade_job.result_hash IS '최종 결과 해시';\n"
                + "COMMENT ON COLUMN public.grade_job.error_code IS '실패 코드';\n"
                + "COMMENT ON COLUMN public.grade_job.created_at IS '생성 시각';\n"
                + "COMMENT ON COLUMN public.grade_job.updated_at IS '상태 변경 시각';\n"
                + "COMMENT ON TABLE public.grade_attempt IS '판정 호출 시도. 예약 후 장애에도 예산 반환 금지';\n"
                + "COMMENT ON COLUMN public.grade_attempt.job_id IS '작업';\n"
                + "COMMENT ON COLUMN public.grade_attempt.attempt_no IS '예약 호출 번호';\n"
                + "COMMENT ON COLUMN public.grade_attempt.lease_gen IS '호출 당시 임대 세대';\n"
                + "COMMENT ON COLUMN public.grade_attempt.worker_key IS '등록 실행기';\n"
                + "COMMENT ON COLUMN public.grade_attempt.started_at IS '호출 예약 확정 시각. 제공자 수신 증명"
                + " 아님';\n"
                + "COMMENT ON COLUMN public.grade_attempt.ended_at IS '시도 종료 시각';\n"
                + "COMMENT ON COLUMN public.grade_attempt.state IS '시도 상태';\n"
                + "COMMENT ON COLUMN public.grade_attempt.provider_ref IS '제공자 응답 참조';\n"
                + "COMMENT ON COLUMN public.grade_attempt.error_code IS '오류 코드';\n"
                + "COMMENT ON COLUMN public.grade_attempt.output_hash IS '수신 출력 해시';\n"
                + "COMMENT ON COLUMN public.grade_attempt.output_cipher IS '필요한 판정 출력 제한 보관"
                + " AES-256-GCM 봉투. AAD는 테이블·행·필드·포맷';\n"
                + "COMMENT ON COLUMN public.grade_attempt.observed_version IS '실제 관측 제공자 버전';\n"
                + "COMMENT ON COLUMN public.grade_attempt.completion_data IS '원문 없는 수집 영수증·적용"
                + " 결과';\n"
                + "\n"
                + "COMMENT ON CONSTRAINT pk_grade_runtime ON public.grade_runtime IS '행 식별"
                + " 무결성';\n"
                + "COMMENT ON CONSTRAINT uk_grade_runtime_code ON public.grade_runtime IS '설정 코드"
                + " 중복 금지';\n"
                + "COMMENT ON CONSTRAINT ck_runtime_state ON public.grade_runtime IS '허용 운영"
                + " 상태';\n"
                + "COMMENT ON CONSTRAINT ck_runtime_epoch ON public.grade_runtime IS '음수 세대"
                + " 금지';\n"
                + "COMMENT ON CONSTRAINT ck_grade_runtime_config_hash ON public.grade_runtime IS"
                + " '소문자 SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_runtime_config_data ON public.grade_runtime IS"
                + " '구성 객체와 UTF-8 저장 크기 상한';\n"
                + "COMMENT ON CONSTRAINT pk_grade_batch ON public.grade_batch IS '행 식별 무결성';\n"
                + "COMMENT ON CONSTRAINT uk_grade_batch_key ON public.grade_batch IS '회귀 집합 키 중복"
                + " 금지';\n"
                + "COMMENT ON CONSTRAINT uk_grade_batch_snapshot ON public.grade_batch IS '같은 사본"
                + " 복합 참조 대상';\n"
                + "COMMENT ON CONSTRAINT ck_gb_purpose ON public.grade_batch IS '검수·운영 점검 목적"
                + " 제한';\n"
                + "COMMENT ON CONSTRAINT ck_gb_state ON public.grade_batch IS '허용 집합 상태';\n"
                + "COMMENT ON CONSTRAINT ck_gb_size ON public.grade_batch IS '정확히 세 반복과 양수 전체 작업"
                + " 수·가용성 세대';\n"
                + "COMMENT ON CONSTRAINT ck_gb_result ON public.grade_batch IS '종료 시각과 상태 일치·완료"
                + " 상태에서만 기대 통과 값 허용';\n"
                + "COMMENT ON CONSTRAINT ck_grade_batch_dataset_hash ON public.grade_batch IS"
                + " '소문자 SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_batch_rubric_hash ON public.grade_batch IS"
                + " '소문자 SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_batch_payload_hash ON public.grade_batch IS"
                + " '소문자 SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_batch_config_hash ON public.grade_batch IS"
                + " '소문자 SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT fk_gb_snapshot ON public.grade_batch IS '존재하는 고정 사본"
                + " 참조·삭제 전파 금지';\n"
                + "COMMENT ON CONSTRAINT fk_gb_runtime ON public.grade_batch IS '존재하는 실행 설정"
                + " 참조·삭제 전파 금지';\n"
                + "COMMENT ON CONSTRAINT fk_gb_admin ON public.grade_batch IS '존재하는 요청 관리자 참조·삭제"
                + " 전파 금지';\n"
                + "COMMENT ON CONSTRAINT pk_grade_job ON public.grade_job IS '행 식별 무결성';\n"
                + "COMMENT ON CONSTRAINT uk_grade_job_key ON public.grade_job IS '작업 키 중복 금지';\n"
                + "COMMENT ON CONSTRAINT uk_grade_job_sample ON public.grade_job IS '집합 내"
                + " fixture 반복 중복 금지';\n"
                + "COMMENT ON CONSTRAINT ck_gj_state ON public.grade_job IS '허용 작업 상태';\n"
                + "COMMENT ON CONSTRAINT ck_gj_source ON public.grade_job IS '필수 회귀 출처와 세 반복"
                + " 번호';\n"
                + "COMMENT ON CONSTRAINT ck_gj_running ON public.grade_job IS '실행 중 상태와 임대 주체"
                + " 일치';\n"
                + "COMMENT ON CONSTRAINT ck_gj_budget ON public.grade_job IS '예약 호출 0~3회·음수 임대"
                + " 세대 금지';\n"
                + "COMMENT ON CONSTRAINT ck_gj_deadline ON public.grade_job IS '미활성 회귀의 NULL 예산"
                + " 또는 접수 후 정확히 120초';\n"
                + "COMMENT ON CONSTRAINT ck_gj_lease ON public.grade_job IS '임대 주체와 만료 동시 존재';\n"
                + "COMMENT ON CONSTRAINT ck_grade_job_input_hash ON public.grade_job IS '소문자"
                + " SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_job_config_hash ON public.grade_job IS '소문자"
                + " SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_job_rubric_hash ON public.grade_job IS '소문자"
                + " SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_job_result_cipher ON public.grade_job IS '암호"
                + " 봉투 32~524288 바이트';\n"
                + "COMMENT ON CONSTRAINT ck_grade_job_result_data ON public.grade_job IS '결과 객체와"
                + " UTF-8 저장 크기 상한';\n"
                + "COMMENT ON CONSTRAINT ck_grade_job_result_hash ON public.grade_job IS '소문자"
                + " SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT fk_gj_snapshot ON public.grade_job IS '존재하는 고정 사본 참조·삭제"
                + " 전파 금지';\n"
                + "COMMENT ON CONSTRAINT fk_gj_runtime ON public.grade_job IS '존재하는 실행 설정 참조·삭제"
                + " 전파 금지';\n"
                + "COMMENT ON CONSTRAINT fk_gj_batch ON public.grade_job IS '작업과 집합의 사본 일치·삭제 전파"
                + " 금지';\n"
                + "COMMENT ON CONSTRAINT pk_grade_attempt ON public.grade_attempt IS '작업별 예약 호출"
                + " 번호 중복 금지';\n"
                + "COMMENT ON CONSTRAINT uk_ga_lease ON public.grade_attempt IS '같은 작업 임대 세대의 호출"
                + " 재예약 금지';\n"
                + "COMMENT ON CONSTRAINT ck_ga_state ON public.grade_attempt IS '허용 시도 상태';\n"
                + "COMMENT ON CONSTRAINT ck_ga_no ON public.grade_attempt IS '호출 번호 1~3과 양수 임대"
                + " 세대';\n"
                + "COMMENT ON CONSTRAINT ck_ga_end ON public.grade_attempt IS '시도 종료 시각과 상태"
                + " 일치';\n"
                + "COMMENT ON CONSTRAINT ck_grade_attempt_output_hash ON public.grade_attempt IS"
                + " '소문자 SHA-256 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_attempt_output_cipher ON public.grade_attempt"
                + " IS '암호 봉투 32~524288 바이트';\n"
                + "COMMENT ON CONSTRAINT ck_grade_attempt_completion_data ON"
                + " public.grade_attempt IS '수집 영수증 객체와 UTF-8 저장 크기 상한';\n"
                + "COMMENT ON CONSTRAINT fk_ga_job ON public.grade_attempt IS '존재하는 작업 참조·삭제 전파"
                + " 금지';\n"
                + "\n"
                + "COMMENT ON INDEX public.pk_grade_runtime IS '행 식별 무결성 자동 인덱스. 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_grade_runtime_code IS '설정 코드 중복 방지 자동 인덱스. 성능"
                + " 미측정';\n"
                + "COMMENT ON INDEX public.pk_grade_batch IS '행 식별 무결성 자동 인덱스. 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_grade_batch_key IS '집합 키 중복 방지 자동 인덱스. 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_grade_batch_snapshot IS '같은 사본 참조 무결성 자동 인덱스. 성능"
                + " 미측정';\n"
                + "COMMENT ON INDEX public.pk_grade_job IS '행 식별 무결성 자동 인덱스. 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_grade_job_key IS '작업 키 중복 방지 자동 인덱스. 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_grade_job_sample IS 'fixture 반복 중복 방지 자동 인덱스. 성능"
                + " 미측정';\n"
                + "COMMENT ON INDEX public.uk_gj_worker IS '실행기당 활성 임대 하나의 무결성. 만료 정비·claim 직렬화는"
                + " 서비스 책임. 성능 미측정';\n"
                + "COMMENT ON INDEX public.pk_grade_attempt IS '예약 호출 번호 중복 방지 자동 인덱스. 성능"
                + " 미측정';\n"
                + "COMMENT ON INDEX public.uk_ga_lease IS '임대 세대별 단일 예약 무결성 자동 인덱스. 성능 미측정';\n";
    }

    /** V14 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql14() {
        return "-- 승인된 최소 판정 감사 구조만 추가한다. 일반 DB 미적용·완료/복구 서비스 구현 아님.\n"
                + "-- 이력 보존은 신뢰된 소유자와 앱 INSERT/SELECT 권한 분리를 전제로 한다.\n"
                + "-- 운영 역할 배포는 별도 절차이며 소유자·superuser 불변성을 주장하지 않는다.\n"
                + "CREATE TABLE public.grade_event (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    job_id bigint NOT NULL,\n"
                + "    attempt_no smallint,\n"
                + "    actor_kind varchar(8) NOT NULL,\n"
                + "    actor_key varchar(80) NOT NULL,\n"
                + "    event_kind varchar(24) NOT NULL,\n"
                + "    command_key uuid NOT NULL,\n"
                + "    request_id uuid,\n"
                + "    command_hash char(64) NOT NULL,\n"
                + "    detail jsonb NOT NULL,\n"
                + "    created_at timestamptz DEFAULT clock_timestamp() NOT NULL,\n"
                + "    CONSTRAINT pk_grade_event PRIMARY KEY (id),\n"
                + "    CONSTRAINT uk_grade_event_command UNIQUE (actor_kind, actor_key,"
                + " command_key),\n"
                + "    CONSTRAINT fk_grade_event_job FOREIGN KEY (job_id) REFERENCES"
                + " public.grade_job (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "    CONSTRAINT fk_grade_event_attempt FOREIGN KEY (job_id, attempt_no)"
                + " REFERENCES public.grade_attempt (job_id, attempt_no) MATCH SIMPLE ON DELETE"
                + " NO ACTION ON UPDATE NO ACTION,\n"
                + "    CONSTRAINT ck_grade_event_attempt CHECK (attempt_no IS NULL OR attempt_no"
                + " BETWEEN 1 AND 3),\n"
                + "    CONSTRAINT ck_grade_event_actor_key CHECK (actor_key COLLATE \"C\" ~"
                + " '^[A-Za-z0-9_-]{1,80}$'),\n"
                + "    CONSTRAINT ck_grade_event_command_hash CHECK (command_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_grade_event_detail CHECK (jsonb_typeof(detail) = 'object'"
                + " AND octet_length(detail::text) <= 4096),\n"
                + "    CONSTRAINT ck_grade_event_shape CHECK (\n"
                + "        (actor_kind = 'WORKER' AND event_kind IN"
                + " ('COMPLETE_APPLIED','COMPLETE_REJECTED') AND attempt_no IS NOT NULL AND"
                + " request_id IS NOT NULL)\n"
                + "        OR (actor_kind = 'SYSTEM' AND event_kind = 'RECOVERY_EXPIRED' AND"
                + " attempt_no IS NOT NULL AND request_id IS NULL)\n"
                + "        OR (actor_kind = 'SYSTEM' AND event_kind IN"
                + " ('JOB_ACTIVATED','JOB_INPUT_REJECTED','JOB_SOURCE_CANCELLED') AND attempt_no"
                + " IS NULL AND request_id IS NULL)\n"
                + "    )\n"
                + ");\n"
                + "\n"
                + "ALTER TABLE public.access_history ADD COLUMN worker_key varchar(80);\n"
                + "ALTER TABLE public.access_history DROP CONSTRAINT ck_access_history_kind;\n"
                + "ALTER TABLE public.access_history DROP CONSTRAINT ck_access_history_actor;\n"
                + "ALTER TABLE public.access_history DROP CONSTRAINT ck_access_history_shape;\n"
                + "ALTER TABLE public.access_history ADD CONSTRAINT ck_access_history_kind CHECK"
                + " (kind IN ('SERVER','NAV') AND actor_kind IN"
                + " ('ADMIN','MEMBER','ANONYMOUS','WORKER'));\n"
                + "ALTER TABLE public.access_history ADD CONSTRAINT ck_access_history_actor"
                + " CHECK (\n"
                + "    (actor_kind = 'ANONYMOUS' AND actor_key IS NULL AND worker_key IS NULL)\n"
                + "    OR (actor_kind IN ('ADMIN','MEMBER') AND actor_key IS NOT NULL AND"
                + " worker_key IS NULL)\n"
                + "    OR (actor_kind = 'WORKER' AND actor_key IS NULL AND worker_key IS NOT"
                + " NULL AND worker_key COLLATE \"C\" ~ '^[A-Za-z0-9_-]{1,80}$')\n"
                + ");\n"
                + "ALTER TABLE public.access_history ADD CONSTRAINT ck_access_history_shape"
                + " CHECK (\n"
                + "    (kind='SERVER' AND route IS NOT NULL AND method IS NOT NULL AND"
                + " started_at IS NOT NULL AND event_key IS NULL AND screen_code IS NULL AND"
                + " from_screen_code IS NULL AND client_at IS NULL)\n"
                + "    OR (kind='NAV' AND actor_kind IN ('ADMIN','MEMBER') AND event_key IS NOT"
                + " NULL AND screen_code IS NOT NULL AND http_status IS NULL AND duration_ms IS"
                + " NULL AND route IS NULL AND method IS NULL AND started_at IS NULL AND"
                + " ended_at IS NULL AND error_code IS NULL AND target_kind IS NULL AND"
                + " target_key IS NULL)\n"
                + ");\n"
                + "\n"
                + "COMMENT ON TABLE public.grade_event IS '실제 작업의 최소 판정 감사 사건. 수집 영수증과 별도이며 서비스"
                + " 감사 구현 완료 아님. 앱 INSERT/SELECT와 신뢰된 소유자 분리 전제';\n"
                + "COMMENT ON COLUMN public.grade_event.id IS 'DB가 생성하는 실제 감사 식별자';\n"
                + "COMMENT ON COLUMN public.grade_event.job_id IS '존재하는 판정 작업. 잘못된 대상의 가짜 작업 생성"
                + " 금지';\n"
                + "COMMENT ON COLUMN public.grade_event.attempt_no IS '실제 예약 호출 번호 1~3. 활성화·입력"
                + " 오류·출처 취소는 NULL이며 가짜 시도 생성 금지';\n"
                + "COMMENT ON COLUMN public.grade_event.actor_kind IS '검증된 WORKER 또는 배포 SYSTEM"
                + " 주체. 관리자 대리 실행 아님';\n"
                + "COMMENT ON COLUMN public.grade_event.actor_key IS '실제 자격 레지스트리 실행기 또는 신뢰된 배포"
                + " coordinator 식별자. 호출자 제공 자격 아님';\n"
                + "COMMENT ON COLUMN public.grade_event.event_kind IS '적용·거절·복구 만료·활성화·입력 거절·출처"
                + " 취소 사건';\n"
                + "COMMENT ON COLUMN public.grade_event.command_key IS '서버 내부 명령 UUID. API 본문이나"
                + " 수집 영수증 필드 아님';\n"
                + "COMMENT ON COLUMN public.grade_event.request_id IS '완료 콜백의 접근 이력 요청 UUID."
                + " SYSTEM 사건은 NULL';\n"
                + "COMMENT ON COLUMN public.grade_event.command_hash IS '서버 내부 정규 명령 소문자"
                + " SHA-256. 원문·자격 값 제외';\n"
                + "COMMENT ON COLUMN public.grade_event.detail IS '원문·토큰 없는 감사 객체. UTF-8 JSONB"
                + " 텍스트 4096바이트 이하';\n"
                + "COMMENT ON COLUMN public.grade_event.created_at IS '감사 INSERT에서 관측한 DB 시각."
                + " 트랜잭션 시작 시각 아님';\n"
                + "COMMENT ON CONSTRAINT pk_grade_event ON public.grade_event IS '감사 행 식별"
                + " 무결성';\n"
                + "COMMENT ON CONSTRAINT uk_grade_event_command ON public.grade_event IS '주체별 서버"
                + " 내부 명령 중복 금지. 수집 영수증 재생은 새 감사 아님';\n"
                + "COMMENT ON CONSTRAINT fk_grade_event_job ON public.grade_event IS '실제 작업 참조."
                + " 삭제·갱신 전파 금지';\n"
                + "COMMENT ON CONSTRAINT fk_grade_event_attempt ON public.grade_event IS '존재하는"
                + " 작업별 실제 시도 참조. NULL 시도는 MATCH SIMPLE. 삭제·갱신 전파 금지';\n"
                + "COMMENT ON CONSTRAINT ck_grade_event_attempt ON public.grade_event IS 'NULL"
                + " 또는 실제 시도 1~3. 0번 시도 금지';\n"
                + "COMMENT ON CONSTRAINT ck_grade_event_actor_key ON public.grade_event IS"
                + " 'ASCII 실행 주체 식별자 1~80자';\n"
                + "COMMENT ON CONSTRAINT ck_grade_event_command_hash ON public.grade_event IS"
                + " '소문자 SHA-256 64자 형식';\n"
                + "COMMENT ON CONSTRAINT ck_grade_event_detail ON public.grade_event IS '감사 객체와"
                + " UTF-8 저장 크기 상한';\n"
                + "COMMENT ON CONSTRAINT ck_grade_event_shape ON public.grade_event IS '주체·사건별"
                + " 실제 시도 및 접근 요청 NULL 조합 제한';\n"
                + "COMMENT ON INDEX public.pk_grade_event IS '행 식별 무결성 자동 인덱스. 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_grade_event_command IS '주체별 명령 중복 방지 무결성 자동 인덱스."
                + " 성능 미측정';\n"
                + "COMMENT ON COLUMN public.access_history.worker_key IS '실제 인증된 실행기 문자열 식별자."
                + " WORKER만 사용하며 UUID actor_key로 대체 금지';\n"
                + "COMMENT ON CONSTRAINT ck_access_history_kind ON public.access_history IS '서버"
                + " 접근·화면 이동 및 관리자·회원·익명·실행기 종류 제한';\n"
                + "COMMENT ON CONSTRAINT ck_access_history_actor ON public.access_history IS"
                + " '실행기는 worker_key만, 관리자·회원은 UUID actor_key만, 익명은 둘 다 NULL';\n"
                + "COMMENT ON CONSTRAINT ck_access_history_shape ON public.access_history IS '기존"
                + " 서버 접근 필드 유지. 화면 이동은 관리자·회원만 허용';\n";
    }

    /** V15 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql15() {
        return "-- 2026-10-02 승인된 최소 감사: 실제 START 예약 없는 만료 RUNNING 임대 회수를 별도 사건으로 허용한다.\n"
                   + "-- 일반 DB에는 적용하지 않았으며 실제 시도가 필요한 RECOVERY_EXPIRED와 기존 사건 조합은 유지한다.\n"
                   + "ALTER TABLE public.grade_event DROP CONSTRAINT ck_grade_event_shape;\n"
                   + "ALTER TABLE public.grade_event ADD CONSTRAINT ck_grade_event_shape CHECK (\n"
                   + "    (actor_kind = 'WORKER' AND event_kind IN"
                   + " ('COMPLETE_APPLIED','COMPLETE_REJECTED') AND attempt_no IS NOT NULL AND"
                   + " request_id IS NOT NULL)\n"
                   + "    OR (actor_kind = 'SYSTEM' AND event_kind = 'RECOVERY_EXPIRED' AND"
                   + " attempt_no IS NOT NULL AND request_id IS NULL)\n"
                   + "    OR (actor_kind = 'SYSTEM' AND event_kind IN"
                   + " ('JOB_ACTIVATED','JOB_INPUT_REJECTED','JOB_SOURCE_CANCELLED','JOB_LEASE_RECLAIMED')"
                   + " AND attempt_no IS NULL AND request_id IS NULL)\n"
                   + ");\n"
                   + "COMMENT ON CONSTRAINT ck_grade_event_shape ON public.grade_event IS '주체·사건별"
                   + " 실제 시도 및 접근 요청 NULL 조합 제한';\n";
    }

    /** V16 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql16() {
        return "-- review_record!A58:A87의 기능 우선 12열 부분집합. 일반 DB 미적용·SR-02 서비스 구현 아님.\n"
                + "-- STRUCTURE/MODEL/APPROVAL만 저장한다. evidence_set_id와 실제 근거 FK, PLAYTEST/GRADE는"
                + " 함께 후속 연결한다.\n"
                + "-- IDX-027/062 무결성 인덱스만 생성하며 IDX-028 조회 후보는 미적용·성능 미측정이다.\n"
                + "-- 앱의 INSERT/SELECT 권한과 신뢰된 소유자 분리가 전제다. 운영 역할 생성·배포 및 소유자/superuser 불변성 보장"
                + " 아님.\n"
                + "CREATE TABLE public.review_record (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    snapshot_id bigint NOT NULL,\n"
                + "    kind varchar(16) NOT NULL,\n"
                + "    request_key uuid NOT NULL,\n"
                + "    evidence_data jsonb NOT NULL,\n"
                + "    result varchar(12) NOT NULL,\n"
                + "    reviewer_id bigint NOT NULL,\n"
                + "    model_id varchar(80),\n"
                + "    effort varchar(16),\n"
                + "    evidence text NOT NULL,\n"
                + "    self_review_yn boolean NOT NULL,\n"
                + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    CONSTRAINT pk_review_record PRIMARY KEY (id),\n"
                + "    CONSTRAINT uq_review_record_request UNIQUE (snapshot_id, request_key),\n"
                + "    CONSTRAINT ck_review_record_kind CHECK (kind IN"
                + " ('STRUCTURE','MODEL','APPROVAL')),\n"
                + "    CONSTRAINT ck_review_record_result CHECK (result IN"
                + " ('PASS','FAIL','INCOMPLETE')),\n"
                + "    CONSTRAINT ck_review_record_evidence CHECK (char_length(btrim(evidence))"
                + " BETWEEN 1 AND 20000),\n"
                + "    CONSTRAINT ck_review_record_data CHECK (jsonb_typeof(evidence_data) ="
                + " 'object' AND octet_length(evidence_data::text) <= 131072),\n"
                + "    CONSTRAINT fk_review_record_snapshot FOREIGN KEY (snapshot_id) REFERENCES"
                + " public.review_snapshot (id) ON DELETE NO ACTION ON UPDATE NO ACTION,\n"
                + "    CONSTRAINT fk_review_record_reviewer FOREIGN KEY (reviewer_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION\n"
                + ");\n"
                + "\n"
                + "-- PUBLIC 및 기본 PUBLIC 경로의 변경 권한을 닫는다. 별도 직접·상속 권한은 배포 주체의 검증 책임이다.\n"
                + "REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.review_record,"
                + " public.review_snapshot FROM PUBLIC;\n"
                + "\n"
                + "COMMENT ON TABLE public.review_record IS '검수 결과. 현재 STRUCTURE/MODEL/APPROVAL"
                + " 3종 부분집합. 실제 근거 연결과 PLAYTEST/GRADE는 후속 구현. 앱 INSERT/SELECT와 신뢰된 소유자 분리 전제';\n"
                + "COMMENT ON COLUMN public.review_record.id IS '내부 식별자';\n"
                + "COMMENT ON COLUMN public.review_record.snapshot_id IS '대상 고정 사본';\n"
                + "COMMENT ON COLUMN public.review_record.kind IS '검수 종류. 현재"
                + " STRUCTURE/MODEL/APPROVAL만 허용';\n"
                + "COMMENT ON COLUMN public.review_record.request_key IS '결과 등록 중복 방지 키';\n"
                + "COMMENT ON COLUMN public.review_record.evidence_data IS '요청 revision 및 구조화"
                + " 근거';\n"
                + "COMMENT ON COLUMN public.review_record.result IS '결과';\n"
                + "COMMENT ON COLUMN public.review_record.reviewer_id IS '책임 관리자';\n"
                + "COMMENT ON COLUMN public.review_record.model_id IS '실제 호출 모델';\n"
                + "COMMENT ON COLUMN public.review_record.effort IS '실제 추론 수준';\n"
                + "COMMENT ON COLUMN public.review_record.evidence IS '검수 결과·근거 위치·수정 요구';\n"
                + "COMMENT ON COLUMN public.review_record.self_review_yn IS '대상 작성·수정 참여자 여부';\n"
                + "COMMENT ON COLUMN public.review_record.created_at IS '결과 기록 시각';\n"
                + "COMMENT ON CONSTRAINT pk_review_record ON public.review_record IS '검수 결과 내부"
                + " 식별 무결성';\n"
                + "COMMENT ON CONSTRAINT uq_review_record_request ON public.review_record IS '고정"
                + " 사본별 결과 등록 중복 방지 키';\n"
                + "COMMENT ON CONSTRAINT ck_review_record_kind ON public.review_record IS '현재 3종"
                + " 검수만 허용. PLAYTEST/GRADE는 실제 근거 연결 후 확장';\n"
                + "COMMENT ON CONSTRAINT ck_review_record_result ON public.review_record IS"
                + " 'PASS/FAIL/INCOMPLETE 결과 제한';\n"
                + "COMMENT ON CONSTRAINT ck_review_record_evidence ON public.review_record IS '양"
                + " 끝 공백 제거 후 근거 1~20000자';\n"
                + "COMMENT ON CONSTRAINT ck_review_record_data ON public.review_record IS '구조화"
                + " 근거 객체 및 JSONB 텍스트 131072바이트 상한';\n"
                + "COMMENT ON CONSTRAINT fk_review_record_snapshot ON public.review_record IS"
                + " '실제 고정 사본 참조. 삭제·갱신 전파 금지';\n"
                + "COMMENT ON CONSTRAINT fk_review_record_reviewer ON public.review_record IS"
                + " '실제 책임 관리자 참조. 삭제·갱신 전파 금지';\n"
                + "COMMENT ON INDEX public.pk_review_record IS 'IDX-027 내부 식별 무결성 자동 인덱스. 성능"
                + " 미측정';\n"
                + "COMMENT ON INDEX public.uq_review_record_request IS 'IDX-062 사본별 요청 중복 방지 무결성"
                + " 자동 인덱스. 성능 미측정';\n";
    }

    /** V17 원본의 공백·이스케이프·마지막 LF를 그대로 반환한다. */
    private static String sql17() {
        return "-- 승인된 BATCH 전용 저장 경계다. 회원·사람 검토·근거·공개 부모와 성능 인덱스는 추가하지 않는다.\n"
                + "ALTER TABLE public.grade_batch ADD CONSTRAINT uk_grade_batch_source UNIQUE"
                + " (snapshot_id, runtime_id, id) NOT DEFERRABLE;\n"
                + "\n"
                + "CREATE TABLE public.test_action (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    request_key uuid NOT NULL,\n"
                + "    admin_id bigint NOT NULL,\n"
                + "    action varchar(40) NOT NULL,\n"
                + "    scope_key varchar(160) NOT NULL,\n"
                + "    request_hash char(64) NOT NULL,\n"
                + "    result_data jsonb NOT NULL,\n"
                + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    CONSTRAINT pk_test_action PRIMARY KEY (id) NOT DEFERRABLE,\n"
                + "    CONSTRAINT uk_test_action_request UNIQUE (request_key) NOT DEFERRABLE,\n"
                + "    CONSTRAINT ck_ta_size CHECK (octet_length(result_data::text) <= 32768),\n"
                + "    CONSTRAINT ck_test_action_request_hash CHECK (request_hash ~"
                + " '^[0-9a-f]{64}$'),\n"
                + "    CONSTRAINT ck_test_action_result_data CHECK (jsonb_typeof(result_data) ="
                + " 'object'),\n"
                + "    CONSTRAINT fk_ta_admin FOREIGN KEY (admin_id) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                + " DEFERRABLE\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.test_audit (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    event_key uuid NOT NULL,\n"
                + "    actor_kind varchar(24) NOT NULL,\n"
                + "    actor_ref varchar(80) NOT NULL,\n"
                + "    action varchar(40) NOT NULL,\n"
                + "    scope_kind varchar(24) NOT NULL,\n"
                + "    scope_key varchar(160) NOT NULL,\n"
                + "    request_id uuid NOT NULL,\n"
                + "    phase varchar(24) NOT NULL,\n"
                + "    business_result varchar(24) NOT NULL,\n"
                + "    detail jsonb NOT NULL,\n"
                + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    CONSTRAINT pk_test_audit PRIMARY KEY (id) NOT DEFERRABLE,\n"
                + "    CONSTRAINT uk_test_audit_event UNIQUE (event_key) NOT DEFERRABLE,\n"
                + "    CONSTRAINT ck_audit_actor_kind CHECK (actor_kind IN"
                + " ('ADMIN','WORKER','SYSTEM')),\n"
                + "    CONSTRAINT ck_audit_phase CHECK (phase IN ('ATTEMPT','RESULT')),\n"
                + "    CONSTRAINT ck_audit_size CHECK (octet_length(detail::text) <= 8192),\n"
                + "    CONSTRAINT ck_test_audit_detail CHECK (jsonb_typeof(detail) = 'object')\n"
                + ");\n"
                + "\n"
                + "CREATE TABLE public.execution_issue (\n"
                + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                + "    issue_key uuid NOT NULL,\n"
                + "    snapshot_id bigint NOT NULL,\n"
                + "    runtime_id bigint NOT NULL,\n"
                + "    batch_id bigint NOT NULL,\n"
                + "    kind varchar(24) NOT NULL,\n"
                + "    severity varchar(24) NOT NULL,\n"
                + "    state varchar(24) NOT NULL,\n"
                + "    reason_code varchar(40) NOT NULL,\n"
                + "    resolved_batch_id bigint,\n"
                + "    resolved_by bigint,\n"
                + "    resolved_at timestamptz,\n"
                + "    resolution_data jsonb,\n"
                + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                + "    CONSTRAINT pk_execution_issue PRIMARY KEY (id) NOT DEFERRABLE,\n"
                + "    CONSTRAINT uk_execution_issue_key UNIQUE (issue_key) NOT DEFERRABLE,\n"
                + "    CONSTRAINT uk_eissue_batch_kind UNIQUE (batch_id, kind) NOT DEFERRABLE,\n"
                + "    CONSTRAINT ck_ei_kind CHECK (kind IN"
                + " ('CONTENT','GRADING','INFRA','OBSERVATION')),\n"
                + "    CONSTRAINT ck_ei_severity CHECK (severity IN ('CRITICAL','MINOR')),\n"
                + "    CONSTRAINT ck_ei_state CHECK (state IN ('OPEN','RESOLVED')),\n"
                + "    CONSTRAINT ck_eissue_resolution CHECK (\n"
                + "        (state = 'OPEN' AND resolved_batch_id IS NULL AND resolved_by IS NULL"
                + " AND resolved_at IS NULL AND resolution_data IS NULL)\n"
                + "        OR\n"
                + "        (state = 'RESOLVED' AND kind <> 'CONTENT' AND resolved_batch_id IS"
                + " NOT NULL AND resolved_batch_id <> batch_id AND resolved_by IS NOT NULL AND"
                + " resolved_at IS NOT NULL AND resolved_at >= created_at AND resolution_data IS"
                + " NOT NULL)\n"
                + "    ),\n"
                + "    CONSTRAINT ck_execution_issue_resolution_data CHECK"
                + " (jsonb_typeof(resolution_data) = 'object' AND"
                + " octet_length(resolution_data::text) <= 131072),\n"
                + "    CONSTRAINT fk_eissue_snapshot FOREIGN KEY (snapshot_id) REFERENCES"
                + " public.review_snapshot (id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                + " DEFERRABLE,\n"
                + "    CONSTRAINT fk_eissue_runtime FOREIGN KEY (runtime_id) REFERENCES"
                + " public.grade_runtime (id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                + " DEFERRABLE,\n"
                + "    CONSTRAINT fk_eissue_batch FOREIGN KEY (snapshot_id, runtime_id,"
                + " batch_id) REFERENCES public.grade_batch (snapshot_id, runtime_id, id) ON"
                + " DELETE NO ACTION ON UPDATE NO ACTION NOT DEFERRABLE,\n"
                + "    CONSTRAINT fk_eissue_res_batch FOREIGN KEY (snapshot_id,"
                + " resolved_batch_id) REFERENCES public.grade_batch (snapshot_id, id) ON DELETE"
                + " NO ACTION ON UPDATE NO ACTION NOT DEFERRABLE,\n"
                + "    CONSTRAINT fk_eissue_admin FOREIGN KEY (resolved_by) REFERENCES"
                + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                + " DEFERRABLE\n"
                + ");\n"
                + "\n"
                + "CREATE FUNCTION public.reject_batch_history_mutation() RETURNS trigger"
                + " LANGUAGE plpgsql SECURITY INVOKER AS $$\n"
                + "BEGIN\n"
                + "    RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE ="
                + " 'BATCH_HISTORY_IMMUTABLE';\n"
                + "END;\n"
                + "$$;\n"
                + "\n"
                + "CREATE TRIGGER tr_test_action_immutable BEFORE UPDATE OR DELETE OR TRUNCATE"
                + " ON public.test_action FOR EACH STATEMENT EXECUTE FUNCTION"
                + " public.reject_batch_history_mutation();\n"
                + "CREATE TRIGGER tr_test_audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON"
                + " public.test_audit FOR EACH STATEMENT EXECUTE FUNCTION"
                + " public.reject_batch_history_mutation();\n"
                + "\n"
                + "CREATE FUNCTION public.guard_batch_issue_history() RETURNS trigger LANGUAGE"
                + " plpgsql SECURITY INVOKER AS $$\n"
                + "BEGIN\n"
                + "    IF TG_OP = 'DELETE' THEN\n"
                + "        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE ="
                + " 'BATCH_ISSUE_IMMUTABLE';\n"
                + "    END IF;\n"
                + "    IF TG_OP = 'INSERT' THEN\n"
                + "        IF NEW.state IS DISTINCT FROM 'OPEN' THEN\n"
                + "            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                + " 'BATCH_ISSUE_MUST_OPEN';\n"
                + "        END IF;\n"
                + "        RETURN NEW;\n"
                + "    END IF;\n"
                + "    IF OLD.state IS DISTINCT FROM 'OPEN' OR NEW.state IS DISTINCT FROM"
                + " 'RESOLVED'\n"
                + "       OR ROW(NEW.id, NEW.issue_key, NEW.snapshot_id, NEW.runtime_id,"
                + " NEW.batch_id, NEW.kind, NEW.severity, NEW.reason_code, NEW.created_at)\n"
                + "          IS DISTINCT FROM\n"
                + "          ROW(OLD.id, OLD.issue_key, OLD.snapshot_id, OLD.runtime_id,"
                + " OLD.batch_id, OLD.kind, OLD.severity, OLD.reason_code, OLD.created_at)"
                + " THEN\n"
                + "        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                + " 'BATCH_ISSUE_HISTORY_CONFLICT';\n"
                + "    END IF;\n"
                + "    RETURN NEW;\n"
                + "END;\n"
                + "$$;\n"
                + "\n"
                + "CREATE TRIGGER tr_execution_issue_history BEFORE INSERT OR UPDATE OR DELETE"
                + " ON public.execution_issue FOR EACH ROW EXECUTE FUNCTION"
                + " public.guard_batch_issue_history();\n"
                + "CREATE TRIGGER tr_execution_issue_no_truncate BEFORE TRUNCATE ON"
                + " public.execution_issue FOR EACH STATEMENT EXECUTE FUNCTION"
                + " public.reject_batch_history_mutation();\n"
                + "\n"
                + "REVOKE ALL ON TABLE public.test_action, public.test_audit,"
                + " public.execution_issue FROM PUBLIC;\n"
                + "REVOKE ALL ON FUNCTION public.reject_batch_history_mutation(),"
                + " public.guard_batch_issue_history() FROM PUBLIC;\n"
                + "\n"
                + "COMMENT ON TABLE public.test_action IS 'BATCH 관리자 명령 영수증. 현재 권한 검사 후 전역 키"
                + " 조회·동일 의도 대조는 서비스 책임이며 업무·필수 감사와 같은 트랜잭션으로 확정한다. 회원 부모는 이 범위에 없다.';\n"
                + "COMMENT ON TABLE public.test_audit IS 'BATCH 비밀 없는 업무 감사. 원본"
                + " URL·query·body·보고서·정답·토큰은 금지하며 필수 RESULT 실패는 업무와 함께 롤백한다. 논리 참조는 권한 증명이"
                + " 아니다.';\n"
                + "COMMENT ON TABLE public.execution_issue IS 'BATCH 실제 출처 지적. 출처·분류는 불변이고 해소는"
                + " OPEN에서 RESOLVED로 단회 기록한다. 사람 검토·회원 파기·근거·공개 게이트는 이 범위에 없다. 실행 품질과 현재 권한은 서비스"
                + " 검증이다.';\n"
                + "\n"
                + "COMMENT ON COLUMN public.test_action.id IS '내부 식별자; 항상 DB identity 생성';\n"
                + "COMMENT ON COLUMN public.test_action.request_key IS '전역 명령 재전송 UUID 키; 인증"
                + " 수단이나 UUID 버전 검증이 아님';\n"
                + "COMMENT ON COLUMN public.test_action.admin_id IS '실제 행위 관리자 필수 참조; 활성·현재"
                + " REVIEW 권한은 서비스 검사';\n"
                + "COMMENT ON COLUMN public.test_action.action IS '서버 허용 명령 코드; 임의 요청 원문 금지';\n"
                + "COMMENT ON COLUMN public.test_action.scope_key IS '서버 정규형 논리 대상 참조; 생성 시"
                + " version 내부 식별자 범위, 원본 URL 금지';\n"
                + "COMMENT ON COLUMN public.test_action.request_hash IS '공유 canonical UTF-8 입력"
                + " SHA-256 소문자 hex; jsonb 텍스트 해시나 인증 증명이 아님';\n"
                + "COMMENT ON COLUMN public.test_action.result_data IS '비민감 결과 식별자·수정번호 객체;"
                + " UTF-8 SQL jsonb 텍스트 32768바이트 이하';\n"
                + "COMMENT ON COLUMN public.test_action.created_at IS '트랜잭션 시작 기준 생성 시각';\n"
                + "COMMENT ON COLUMN public.test_audit.id IS '내부 식별자; 항상 DB identity 생성';\n"
                + "COMMENT ON COLUMN public.test_audit.event_key IS '감사 이벤트 UUID 키; 인증 수단 아님';\n"
                + "COMMENT ON COLUMN public.test_audit.actor_kind IS 'ADMIN·WORKER·SYSTEM 출처 표지;"
                + " MEMBER 제외, 실행기에게 관리자 또는 DB 권한을 부여하지 않음';\n"
                + "COMMENT ON COLUMN public.test_audit.actor_ref IS '서버가 검증한 내부 주체 논리 참조; 다형 FK나"
                + " 요청 body 복사 아님';\n"
                + "COMMENT ON COLUMN public.test_audit.action IS '서버 허용 행동 코드';\n"
                + "COMMENT ON COLUMN public.test_audit.scope_kind IS '서버가 결정한 논리 대상 유형';\n"
                + "COMMENT ON COLUMN public.test_audit.scope_key IS '업무 영수증과 같은 서버 정규형 논리 대상 참조;"
                + " FK나 현재 권한 증명 아님';\n"
                + "COMMENT ON COLUMN public.test_audit.request_id IS '서버 요청 연결 UUID; 인증 수단"
                + " 아님';\n"
                + "COMMENT ON COLUMN public.test_audit.phase IS 'ATTEMPT 또는 RESULT 처리 단계';\n"
                + "COMMENT ON COLUMN public.test_audit.business_result IS '서버 허용 업무 결과 코드';\n"
                + "COMMENT ON COLUMN public.test_audit.detail IS '허용된 ID·단계·오류 집계 객체; UTF-8 SQL"
                + " jsonb 텍스트 8192바이트 이하, 비밀·원문 제외';\n"
                + "COMMENT ON COLUMN public.test_audit.created_at IS '트랜잭션 시작 기준 생성 시각';\n"
                + "COMMENT ON COLUMN public.execution_issue.id IS '내부 식별자; 항상 DB identity 생성';\n"
                + "COMMENT ON COLUMN public.execution_issue.issue_key IS '실행 지적 API UUID 참조; 인증"
                + " 수단 아님';\n"
                + "COMMENT ON COLUMN public.execution_issue.snapshot_id IS '지적의 실제 고정 사본';\n"
                + "COMMENT ON COLUMN public.execution_issue.runtime_id IS '지적 출처 집합의 실제 실행"
                + " 설정';\n"
                + "COMMENT ON COLUMN public.execution_issue.batch_id IS '실패·불완전 BATCH 필수 출처;"
                + " 사본·실행 설정과 함께 실제 집합에 결속';\n"
                + "COMMENT ON COLUMN public.execution_issue.kind IS"
                + " 'CONTENT·GRADING·INFRA·OBSERVATION 분류; 기대 음성 비교 PASS는 결함이 아니며 실제 분류는 서비스"
                + " 책임';\n"
                + "COMMENT ON COLUMN public.execution_issue.severity IS 'CRITICAL 또는 MINOR"
                + " 영향도';\n"
                + "COMMENT ON COLUMN public.execution_issue.state IS 'OPEN에서 시작하여 검증 후 RESOLVED로"
                + " 한 번만 전환';\n"
                + "COMMENT ON COLUMN public.execution_issue.reason_code IS '발생 사유 코드; 원문 인용·회원"
                + " 식별자 금지, 이후 변경 불가';\n"
                + "COMMENT ON COLUMN public.execution_issue.resolved_batch_id IS '같은 사본의 다른 실제"
                + " 해소 집합; 다른 runtime 허용은 구조 조건일 뿐 품질 통과 증명 아님';\n"
                + "COMMENT ON COLUMN public.execution_issue.resolved_by IS '해소 확인 실제 관리자; 현재 권한은"
                + " 서비스 검사';\n"
                + "COMMENT ON COLUMN public.execution_issue.resolved_at IS '생성 시각 이상인 해소 시각;"
                + " 서비스가 새 서버 시각으로 기록';\n"
                + "COMMENT ON COLUMN public.execution_issue.resolution_data IS '허용된 원인·검증 참조 객체;"
                + " UTF-8 SQL jsonb 텍스트 131072바이트 이하, OPEN은 SQL NULL';\n"
                + "COMMENT ON COLUMN public.execution_issue.created_at IS '트랜잭션 시작 기준 지적 생성 시각;"
                + " 출처 이력과 함께 불변';\n"
                + "\n"
                + "COMMENT ON CONSTRAINT uk_grade_batch_source ON public.grade_batch IS '지적 출처"
                + " 사본·runtime·집합 결속 FK 대상 UNIQUE; 기존 사본·id 해소 키는 유지';\n"
                + "COMMENT ON CONSTRAINT pk_test_action ON public.test_action IS '영수증 내부 식별 기본"
                + " 키';\n"
                + "COMMENT ON CONSTRAINT uk_test_action_request ON public.test_action IS"
                + " '주체·행동·범위와 무관한 전역 요청 키 유일성; 현재 인증·동일 의도 대조는 서비스 책임';\n"
                + "COMMENT ON CONSTRAINT ck_ta_size ON public.test_action IS 'UTF-8 SQL jsonb"
                + " 텍스트 영수증 32768바이트 상한; HTTP canonical 바이트와 구분';\n"
                + "COMMENT ON CONSTRAINT ck_test_action_request_hash ON public.test_action IS"
                + " '요청 해시 소문자 64자리 hex 형식; 해시 원상·인증 검증 아님';\n"
                + "COMMENT ON CONSTRAINT ck_test_action_result_data ON public.test_action IS"
                + " '영수증 JSON 객체만 허용; 허용 필드·비밀 제외·중복 키 거절은 입력 서비스 책임';\n"
                + "COMMENT ON CONSTRAINT fk_ta_admin ON public.test_action IS '실제 관리자 존재 참조;"
                + " 삭제·키 변경 NO ACTION, 즉시 검사, 현재 권한 아님';\n"
                + "COMMENT ON CONSTRAINT pk_test_audit ON public.test_audit IS '감사 내부 식별 기본"
                + " 키';\n"
                + "COMMENT ON CONSTRAINT uk_test_audit_event ON public.test_audit IS '감사 이벤트 키"
                + " 유일성';\n"
                + "COMMENT ON CONSTRAINT ck_audit_actor_kind ON public.test_audit IS 'BATCH 주체"
                + " 출처 표지 ADMIN·WORKER·SYSTEM만 허용; 권한 부여 아님';\n"
                + "COMMENT ON CONSTRAINT ck_audit_phase ON public.test_audit IS 'ATTEMPT·RESULT"
                + " 단계만 허용';\n"
                + "COMMENT ON CONSTRAINT ck_audit_size ON public.test_audit IS 'UTF-8 SQL jsonb"
                + " 텍스트 최소 감사 8192바이트 상한';\n"
                + "COMMENT ON CONSTRAINT ck_test_audit_detail ON public.test_audit IS '감사 JSON"
                + " 객체만 허용; 비밀 없는 허용 필드는 서비스 책임';\n"
                + "COMMENT ON CONSTRAINT pk_execution_issue ON public.execution_issue IS '지적 내부"
                + " 식별 기본 키';\n"
                + "COMMENT ON CONSTRAINT uk_execution_issue_key ON public.execution_issue IS '지적"
                + " API 참조 키 유일성';\n"
                + "COMMENT ON CONSTRAINT uk_eissue_batch_kind ON public.execution_issue IS '출처"
                + " 집합·분류당 하나의 최초 지적 보존';\n"
                + "COMMENT ON CONSTRAINT ck_ei_kind ON public.execution_issue IS '승인된 지적 분류 코드만"
                + " 허용; OBSERVATION 코드가 사람 검토 지원을 만들지 않음';\n"
                + "COMMENT ON CONSTRAINT ck_ei_severity ON public.execution_issue IS '승인된 영향도"
                + " 코드만 허용';\n"
                + "COMMENT ON CONSTRAINT ck_ei_state ON public.execution_issue IS 'OPEN·RESOLVED"
                + " 상태만 허용';\n"
                + "COMMENT ON CONSTRAINT ck_eissue_resolution ON public.execution_issue IS"
                + " 'OPEN은 해소 필드 전부 NULL, RESOLVED는 CONTENT 제외·다른 집합·관리자·생성 이후 시각·객체 필수; 실제 후속"
                + " 실행 적합성은 서비스 책임';\n"
                + "COMMENT ON CONSTRAINT ck_execution_issue_resolution_data ON"
                + " public.execution_issue IS '해소 값 존재 시 객체 및 UTF-8 SQL jsonb 텍스트 131072바이트"
                + " 상한';\n"
                + "COMMENT ON CONSTRAINT fk_eissue_snapshot ON public.execution_issue IS '실제 사본"
                + " 존재 참조; 삭제·키 변경 NO ACTION, 즉시 검사';\n"
                + "COMMENT ON CONSTRAINT fk_eissue_runtime ON public.execution_issue IS '실제 실행"
                + " 설정 존재 참조; 삭제·키 변경 NO ACTION, 즉시 검사';\n"
                + "COMMENT ON CONSTRAINT fk_eissue_batch ON public.execution_issue IS"
                + " '사본·runtime·집합 순서의 실제 출처 결속; 삭제·키 변경 NO ACTION, 즉시 검사';\n"
                + "COMMENT ON CONSTRAINT fk_eissue_res_batch ON public.execution_issue IS '사본·해소"
                + " 집합 순서 결속; runtime 교정 허용, 삭제·키 변경 NO ACTION, 즉시 검사';\n"
                + "COMMENT ON CONSTRAINT fk_eissue_admin ON public.execution_issue IS '실제 해소 관리자"
                + " 존재 참조; 삭제·키 변경 NO ACTION, 즉시 검사, 현재 권한 아님';\n"
                + "\n"
                + "COMMENT ON INDEX public.pk_test_action IS 'IDX-106 기본 키 제약 소유 BTREE; 무결성 목적,"
                + " 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_test_action_request IS 'IDX-107 전역 요청 키 UNIQUE 제약"
                + " 소유 BTREE; 성능 미측정';\n"
                + "COMMENT ON INDEX public.pk_test_audit IS 'IDX-108 기본 키 제약 소유 BTREE; 성능"
                + " 미측정';\n"
                + "COMMENT ON INDEX public.uk_test_audit_event IS 'IDX-109 이벤트 키 UNIQUE 제약 소유"
                + " BTREE; 성능 미측정';\n"
                + "COMMENT ON INDEX public.pk_execution_issue IS 'IDX-111 기본 키 제약 소유 BTREE; 성능"
                + " 미측정';\n"
                + "COMMENT ON INDEX public.uk_execution_issue_key IS 'IDX-112 지적 키 UNIQUE 제약 소유"
                + " BTREE; 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_eissue_batch_kind IS 'IDX-113 출처·분류 UNIQUE 제약 소유"
                + " BTREE; 성능 미측정';\n"
                + "COMMENT ON INDEX public.uk_grade_batch_source IS 'IDX-195 출처 삼중 FK 대상 UNIQUE"
                + " 제약 소유 BTREE; 성능 인덱스 아님, 성능 미측정';\n"
                + "\n"
                + "COMMENT ON FUNCTION public.reject_batch_history_mutation() IS '일반 역할의 영수증·감사"
                + " 변경과 지적 TRUNCATE 거절 invoker 트리거 함수; 소유자·superuser의 비활성화·DDL 우회 방어가 아님';\n"
                + "COMMENT ON FUNCTION public.guard_batch_issue_history() IS '일반 역할의 지적 OPEN 생성"
                + " 및 불변 출처의 단회 해소만 허용하는 invoker 함수; 현재 인증·실행 품질·필수 감사는 서비스 책임, 소유자·superuser 우회"
                + " 방어 아님';\n"
                + "COMMENT ON TRIGGER tr_test_action_immutable ON public.test_action IS '일반 역할"
                + " UPDATE·DELETE·TRUNCATE 거절; 소유자·superuser가 끄거나 제거할 수 있음';\n"
                + "COMMENT ON TRIGGER tr_test_audit_immutable ON public.test_audit IS '일반 역할"
                + " UPDATE·DELETE·TRUNCATE 거절; 소유자·superuser가 끄거나 제거할 수 있음';\n"
                + "COMMENT ON TRIGGER tr_execution_issue_history ON public.execution_issue IS"
                + " 'OPEN 생성·불변 출처·단회 OPEN→RESOLVED 및 DELETE 거절; 서비스는 해소·영수증·필수 감사 동시 확정';\n"
                + "COMMENT ON TRIGGER tr_execution_issue_no_truncate ON public.execution_issue"
                + " IS '일반 역할의 지적 전체 삭제 거절; 소유자·superuser 우회 방어 아님';\n";
    }

    /** V18 원본의 공백·마지막 LF를 그대로 반환한다. */
    private static String sql18() {
        return "-- GRADE 실제 실행 근거만 추가한다. 실행 품질·현재 자격은 서비스 책임이며 운영 유효기간·회원·PLAYTEST 부모는 만들지 않는다.\n"
                   + "CREATE TABLE public.evidence_set (\n"
                   + "    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "    set_key uuid NOT NULL,\n"
                   + "    snapshot_id bigint NOT NULL,\n"
                   + "    runtime_id bigint NOT NULL,\n"
                   + "    runtime_epoch bigint NOT NULL,\n"
                   + "    kind varchar(16) NOT NULL,\n"
                   + "    evidence_hash char(64) NOT NULL,\n"
                   + "    summary_data jsonb NOT NULL,\n"
                   + "    available_yn boolean DEFAULT true NOT NULL,\n"
                   + "    invalidated_at timestamptz,\n"
                   + "    invalidated_issue_id bigint,\n"
                   + "    created_by bigint NOT NULL,\n"
                   + "    created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "    CONSTRAINT pk_evidence_set PRIMARY KEY (id) NOT DEFERRABLE,\n"
                   + "    CONSTRAINT uq_evidence_set_key UNIQUE (set_key) NOT DEFERRABLE,\n"
                   + "    CONSTRAINT uq_evidence_set_snapshot UNIQUE (snapshot_id,id) NOT"
                   + " DEFERRABLE,\n"
                   + "    CONSTRAINT uq_evidence_set_source UNIQUE (snapshot_id,runtime_id,id) NOT"
                   + " DEFERRABLE,\n"
                   + "    CONSTRAINT ck_evidence_set_epoch CHECK (runtime_epoch >= 0),\n"
                   + "    CONSTRAINT ck_evidence_set_kind CHECK (kind = 'GRADE'),\n"
                   + "    CONSTRAINT ck_evidence_set_hash CHECK (evidence_hash ~"
                   + " '^[0-9a-f]{64}$'),\n"
                   + "    CONSTRAINT ck_evidence_set_summary CHECK (jsonb_typeof(summary_data) ="
                   + " 'object' AND octet_length(summary_data::text) <= 131072),\n"
                   + "    CONSTRAINT ck_evidence_set_invalidation CHECK (\n"
                   + "        (available_yn AND invalidated_at IS NULL AND invalidated_issue_id IS"
                   + " NULL)\n"
                   + "        OR (NOT available_yn AND invalidated_at IS NOT NULL AND"
                   + " invalidated_issue_id IS NOT NULL AND invalidated_at >= created_at)\n"
                   + "    ),\n"
                   + "    CONSTRAINT fk_evidence_set_snapshot FOREIGN KEY (snapshot_id) REFERENCES"
                   + " public.review_snapshot (id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                   + " DEFERRABLE,\n"
                   + "    CONSTRAINT fk_evidence_set_runtime FOREIGN KEY (runtime_id) REFERENCES"
                   + " public.grade_runtime (id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                   + " DEFERRABLE,\n"
                   + "    CONSTRAINT fk_evidence_set_issue FOREIGN KEY (invalidated_issue_id)"
                   + " REFERENCES public.execution_issue (id) ON DELETE NO ACTION ON UPDATE NO"
                   + " ACTION NOT DEFERRABLE,\n"
                   + "    CONSTRAINT fk_evidence_set_creator FOREIGN KEY (created_by) REFERENCES"
                   + " public.admin_account (id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                   + " DEFERRABLE\n"
                   + ");\n"
                   + "\n"
                   + "CREATE TABLE public.evidence_item (\n"
                   + "    set_id bigint NOT NULL,\n"
                   + "    snapshot_id bigint NOT NULL,\n"
                   + "    runtime_id bigint NOT NULL,\n"
                   + "    batch_id bigint NOT NULL,\n"
                   + "    evidence_hash char(64) NOT NULL,\n"
                   + "    CONSTRAINT pk_evidence_item PRIMARY KEY (set_id,batch_id) NOT"
                   + " DEFERRABLE,\n"
                   + "    CONSTRAINT ck_evidence_item_hash CHECK (evidence_hash ~"
                   + " '^[0-9a-f]{64}$'),\n"
                   + "    CONSTRAINT fk_evidence_item_set FOREIGN KEY"
                   + " (snapshot_id,runtime_id,set_id) REFERENCES public.evidence_set"
                   + " (snapshot_id,runtime_id,id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                   + " DEFERRABLE,\n"
                   + "    CONSTRAINT fk_evidence_item_batch FOREIGN KEY"
                   + " (snapshot_id,runtime_id,batch_id) REFERENCES public.grade_batch"
                   + " (snapshot_id,runtime_id,id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT"
                   + " DEFERRABLE\n"
                   + ");\n"
                   + "\n"
                   + "ALTER TABLE public.review_record ADD COLUMN evidence_set_id bigint;\n"
                   + "ALTER TABLE public.review_record DROP CONSTRAINT ck_review_record_kind;\n"
                   + "ALTER TABLE public.review_record ADD CONSTRAINT ck_review_record_kind CHECK"
                   + " (kind IN ('STRUCTURE','MODEL','APPROVAL','GRADE'));\n"
                   + "ALTER TABLE public.review_record ADD CONSTRAINT ck_review_record_set CHECK"
                   + " (\n"
                   + "    (kind = 'GRADE' AND evidence_set_id IS NOT NULL AND result = 'PASS')\n"
                   + "    OR (kind IN ('STRUCTURE','MODEL','APPROVAL') AND evidence_set_id IS"
                   + " NULL)\n"
                   + ");\n"
                   + "ALTER TABLE public.review_record ADD CONSTRAINT uq_review_record_set UNIQUE"
                   + " (evidence_set_id) NOT DEFERRABLE;\n"
                   + "ALTER TABLE public.review_record ADD CONSTRAINT fk_review_record_set FOREIGN"
                   + " KEY (snapshot_id,evidence_set_id) REFERENCES public.evidence_set"
                   + " (snapshot_id,id) ON DELETE NO ACTION ON UPDATE NO ACTION NOT DEFERRABLE;\n"
                   + "\n"
                   + "CREATE FUNCTION public.reject_grade_evidence_mutation() RETURNS trigger"
                   + " LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS"
                   + " $$\n"
                   + "BEGIN\n"
                   + "    RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE ="
                   + " 'GRADE_EVIDENCE_IMMUTABLE';\n"
                   + "END;\n"
                   + "$$;\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_grade_evidence_set() RETURNS trigger LANGUAGE"
                   + " plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS $$\n"
                   + "BEGIN\n"
                   + "    IF TG_OP = 'INSERT' THEN\n"
                   + "        IF NEW.available_yn IS DISTINCT FROM true OR NEW.invalidated_at IS"
                   + " NOT NULL OR NEW.invalidated_issue_id IS NOT NULL THEN\n"
                   + "            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                   + " 'GRADE_EVIDENCE_MUST_START_AVAILABLE';\n"
                   + "        END IF;\n"
                   + "        RETURN NEW;\n"
                   + "    END IF;\n"
                   + "    IF OLD.available_yn IS DISTINCT FROM true OR NEW.available_yn IS DISTINCT"
                   + " FROM false\n"
                   + "       OR NEW.invalidated_at IS NULL OR NEW.invalidated_issue_id IS NULL\n"
                   + "       OR"
                   + " ROW(NEW.id,NEW.set_key,NEW.snapshot_id,NEW.runtime_id,NEW.runtime_epoch,NEW.kind,NEW.evidence_hash,NEW.summary_data,NEW.created_by,NEW.created_at)\n"
                   + "          IS DISTINCT FROM"
                   + " ROW(OLD.id,OLD.set_key,OLD.snapshot_id,OLD.runtime_id,OLD.runtime_epoch,OLD.kind,OLD.evidence_hash,OLD.summary_data,OLD.created_by,OLD.created_at)"
                   + " THEN\n"
                   + "        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                   + " 'GRADE_EVIDENCE_INVALIDATION_CONFLICT';\n"
                   + "    END IF;\n"
                   + "    IF NOT EXISTS (SELECT 1 FROM public.execution_issue WHERE id ="
                   + " NEW.invalidated_issue_id AND snapshot_id = OLD.snapshot_id AND state ="
                   + " 'OPEN' AND NEW.invalidated_at >= created_at) THEN\n"
                   + "        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                   + " 'GRADE_EVIDENCE_ISSUE_CONFLICT';\n"
                   + "    END IF;\n"
                   + "    RETURN NEW;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_grade_evidence_membership() RETURNS trigger"
                   + " LANGUAGE plpgsql SECURITY INVOKER SET search_path = pg_catalog, public AS"
                   + " $$\n"
                   + "DECLARE\n"
                   + "    target_id bigint;\n"
                   + "    usable boolean;\n"
                   + "BEGIN\n"
                   + "    IF TG_TABLE_NAME = 'review_record' THEN\n"
                   + "        IF NEW.kind IS DISTINCT FROM 'GRADE' THEN\n"
                   + "            RETURN NEW;\n"
                   + "        END IF;\n"
                   + "    END IF;\n"
                   + "    -- 이전 스냅샷으로 봉인을 우회하지 않도록 삽입 경계는 READ COMMITTED에서만 사용한다.\n"
                   + "    IF current_setting('transaction_isolation') <> 'read committed' THEN\n"
                   + "        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                   + " 'GRADE_EVIDENCE_ISOLATION_CONFLICT';\n"
                   + "    END IF;\n"
                   + "    IF TG_TABLE_NAME = 'evidence_item' THEN\n"
                   + "        target_id := NEW.set_id;\n"
                   + "    ELSE\n"
                   + "        target_id := NEW.evidence_set_id;\n"
                   + "    END IF;\n"
                   + "    SELECT available_yn INTO usable FROM public.evidence_set WHERE id ="
                   + " target_id FOR UPDATE;\n"
                   + "    IF NOT FOUND THEN\n"
                   + "        RAISE EXCEPTION USING ERRCODE = '23503', MESSAGE ="
                   + " 'GRADE_EVIDENCE_PARENT_REQUIRED';\n"
                   + "    END IF;\n"
                   + "    IF EXISTS (SELECT 1 FROM public.review_record WHERE evidence_set_id ="
                   + " target_id) THEN\n"
                   + "        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                   + " 'GRADE_EVIDENCE_SEALED';\n"
                   + "    END IF;\n"
                   + "    IF TG_TABLE_NAME = 'review_record' AND (usable IS DISTINCT FROM true OR"
                   + " NOT EXISTS (SELECT 1 FROM public.evidence_item WHERE set_id = target_id))"
                   + " THEN\n"
                   + "        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE ="
                   + " 'GRADE_EVIDENCE_ITEMS_REQUIRED';\n"
                   + "    END IF;\n"
                   + "    RETURN NEW;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "\n"
                   + "CREATE TRIGGER tr_evidence_set_history BEFORE INSERT OR UPDATE ON"
                   + " public.evidence_set FOR EACH ROW EXECUTE FUNCTION"
                   + " public.guard_grade_evidence_set();\n"
                   + "CREATE TRIGGER tr_evidence_set_no_remove BEFORE DELETE OR TRUNCATE ON"
                   + " public.evidence_set FOR EACH STATEMENT EXECUTE FUNCTION"
                   + " public.reject_grade_evidence_mutation();\n"
                   + "CREATE TRIGGER tr_evidence_item_membership BEFORE INSERT ON"
                   + " public.evidence_item FOR EACH ROW EXECUTE FUNCTION"
                   + " public.guard_grade_evidence_membership();\n"
                   + "CREATE TRIGGER tr_evidence_item_immutable BEFORE UPDATE OR DELETE OR TRUNCATE"
                   + " ON public.evidence_item FOR EACH STATEMENT EXECUTE FUNCTION"
                   + " public.reject_grade_evidence_mutation();\n"
                   + "CREATE TRIGGER tr_review_record_grade_seal BEFORE INSERT ON"
                   + " public.review_record FOR EACH ROW EXECUTE FUNCTION"
                   + " public.guard_grade_evidence_membership();\n"
                   + "CREATE TRIGGER tr_review_record_immutable BEFORE UPDATE OR DELETE OR TRUNCATE"
                   + " ON public.review_record FOR EACH STATEMENT EXECUTE FUNCTION"
                   + " public.reject_grade_evidence_mutation();\n"
                   + "\n"
                   + "REVOKE ALL ON TABLE public.evidence_set, public.evidence_item FROM PUBLIC;\n"
                   + "REVOKE ALL ON FUNCTION public.reject_grade_evidence_mutation(),"
                   + " public.guard_grade_evidence_set(), public.guard_grade_evidence_membership()"
                   + " FROM PUBLIC;\n"
                   + "\n"
                   + "COMMENT ON TABLE public.evidence_set IS 'GRADE 실제 실행 출처 집합. 최초 지적의 단회 무효화만"
                   + " 허용하며 현재 실행 자격·품질·운영 가용성 증명이 아니다.';\n"
                   + "COMMENT ON TABLE public.evidence_item IS '실제 사본·runtime·BATCH와 결속된 불변 구성원. 검수"
                   + " 기록이 집합을 봉인하며 전체 실행 검증은 서비스 책임이다.';\n"
                   + "COMMENT ON TABLE public.review_record IS 'STRUCTURE/MODEL/APPROVAL 수동 검수와 실제"
                   + " GRADE PASS 근거 연결을 추가 전용으로 보존한다. 소유자·superuser의 DDL 우회 방어가 아니다.';\n"
                   + "COMMENT ON COLUMN public.evidence_set.id IS '항상 DB가 생성하는 내부 식별자';\n"
                   + "COMMENT ON COLUMN public.evidence_set.set_key IS '집합 UUID 참조; 인증 수단 아님';\n"
                   + "COMMENT ON COLUMN public.evidence_set.snapshot_id IS '실제 고정 검수 사본';\n"
                   + "COMMENT ON COLUMN public.evidence_set.runtime_id IS '실제 실행 설정';\n"
                   + "COMMENT ON COLUMN public.evidence_set.runtime_epoch IS '0 이상인 실행 당시 세대; 현재 세대"
                   + " 검사는 서비스 책임';\n"
                   + "COMMENT ON COLUMN public.evidence_set.kind IS 'GRADE만 허용; PLAYTEST 부모 없음';\n"
                   + "COMMENT ON COLUMN public.evidence_set.evidence_hash IS '정규형 실행 집합 SHA-256 소문자"
                   + " hex; 실행 인증 증명 아님';\n"
                   + "COMMENT ON COLUMN public.evidence_set.summary_data IS '최소 집계·고정 해시 객체; 원문"
                   + " 보고서·정답 제외, SQL JSONB 텍스트 131072바이트 상한';\n"
                   + "COMMENT ON COLUMN public.evidence_set.available_yn IS '최초 지적에 의해 비가역적으로 꺼지는"
                   + " 표지; 현재 자격이나 운영 가용성 아님';\n"
                   + "COMMENT ON COLUMN public.evidence_set.invalidated_at IS '생성 이후 최초 무효화 서버 시각;"
                   + " 서비스가 기록';\n"
                   + "COMMENT ON COLUMN public.evidence_set.invalidated_issue_id IS '같은 사본의 실제 최초"
                   + " OPEN 실행 지적; 이후 사유 변경 불가';\n"
                   + "COMMENT ON COLUMN public.evidence_set.created_by IS '실제 생성 관리자; 현재 권한 검사는 서비스"
                   + " 책임';\n"
                   + "COMMENT ON COLUMN public.evidence_set.created_at IS '생성 시각; 이후 불변';\n"
                   + "COMMENT ON COLUMN public.evidence_item.set_id IS '실제 근거 집합 내부 식별자';\n"
                   + "COMMENT ON COLUMN public.evidence_item.snapshot_id IS '집합과 BATCH에 공통인 실제"
                   + " 사본';\n"
                   + "COMMENT ON COLUMN public.evidence_item.runtime_id IS '집합과 BATCH에 공통인 실제 실행"
                   + " 설정';\n"
                   + "COMMENT ON COLUMN public.evidence_item.batch_id IS '전체 실행을 검증한 실제 BATCH; 품질"
                   + " 검사는 서비스 책임';\n"
                   + "COMMENT ON COLUMN public.evidence_item.evidence_hash IS '검증된 전체 실행 명세 SHA-256"
                   + " 소문자 hex';\n"
                   + "COMMENT ON COLUMN public.review_record.evidence_set_id IS 'GRADE PASS의 실제 같은"
                   + " 사본 집합; 수동 검수는 NULL';\n"
                   + "COMMENT ON COLUMN public.review_record.kind IS 'STRUCTURE/MODEL/APPROVAL 수동"
                   + " 검수 또는 실제 집합에 연결된 GRADE';\n"
                   + "COMMENT ON CONSTRAINT pk_evidence_set ON public.evidence_set IS '근거 집합 내부 식별"
                   + " 기본 키';\n"
                   + "COMMENT ON CONSTRAINT uq_evidence_set_key ON public.evidence_set IS '집합 UUID"
                   + " 유일성';\n"
                   + "COMMENT ON CONSTRAINT uq_evidence_set_snapshot ON public.evidence_set IS '검수"
                   + " 기록의 같은 사본 결속 대상';\n"
                   + "COMMENT ON CONSTRAINT uq_evidence_set_source ON public.evidence_set IS '구성원의"
                   + " 같은 사본·실행 설정 결속 대상';\n"
                   + "COMMENT ON CONSTRAINT ck_evidence_set_epoch ON public.evidence_set IS '실행 당시"
                   + " 세대 0 이상';\n"
                   + "COMMENT ON CONSTRAINT ck_evidence_set_kind ON public.evidence_set IS 'GRADE"
                   + " 출처만 허용';\n"
                   + "COMMENT ON CONSTRAINT ck_evidence_set_hash ON public.evidence_set IS 'SHA-256"
                   + " 소문자 64자리 형식; 인증 증명 아님';\n"
                   + "COMMENT ON CONSTRAINT ck_evidence_set_summary ON public.evidence_set IS 'JSON"
                   + " 객체 및 SQL JSONB 텍스트 131072바이트 상한';\n"
                   + "COMMENT ON CONSTRAINT ck_evidence_set_invalidation ON public.evidence_set IS"
                   + " '유효 표지는 무효화 필드 NULL, 무효 표지는 실제 사유와 생성 이후 시각 필수';\n"
                   + "COMMENT ON CONSTRAINT fk_evidence_set_snapshot ON public.evidence_set IS '실제"
                   + " 사본 필수 참조; 즉시 NO ACTION';\n"
                   + "COMMENT ON CONSTRAINT fk_evidence_set_runtime ON public.evidence_set IS '실제"
                   + " 실행 설정 필수 참조; 즉시 NO ACTION';\n"
                   + "COMMENT ON CONSTRAINT fk_evidence_set_issue ON public.evidence_set IS '실제 무효화"
                   + " 지적 참조; 같은 사본·OPEN·시각은 트리거 검사';\n"
                   + "COMMENT ON CONSTRAINT fk_evidence_set_creator ON public.evidence_set IS '실제"
                   + " 관리자 필수 참조; 현재 권한 검사는 서비스 책임';\n"
                   + "COMMENT ON CONSTRAINT pk_evidence_item ON public.evidence_item IS '집합·실제"
                   + " BATCH 복합 기본 키';\n"
                   + "COMMENT ON CONSTRAINT ck_evidence_item_hash ON public.evidence_item IS '검증된"
                   + " 실행 명세 SHA-256 소문자 형식';\n"
                   + "COMMENT ON CONSTRAINT fk_evidence_item_set ON public.evidence_item IS '사본·실행"
                   + " 설정·집합 순서의 실제 결속; 즉시 NO ACTION';\n"
                   + "COMMENT ON CONSTRAINT fk_evidence_item_batch ON public.evidence_item IS 'V17"
                   + " 실제 BATCH 삼중 UNIQUE 키 결속; 즉시 NO ACTION';\n"
                   + "COMMENT ON FUNCTION public.reject_grade_evidence_mutation() IS '일반 역할의 추가 전용"
                   + " 이력 변경·삭제·TRUNCATE를 거절하는 invoker 함수; 소유자·superuser 우회 방어 아님';\n"
                   + "COMMENT ON FUNCTION public.guard_grade_evidence_set() IS '불변 집합의 생성과 같은 사본"
                   + " OPEN 지적에 의한 최초 무효화만 허용한다';\n"
                   + "COMMENT ON FUNCTION public.guard_grade_evidence_membership() IS 'READ"
                   + " COMMITTED에서 구성원 삽입과 GRADE 기록 삽입을 같은 집합 행 잠금으로 직렬화하여 봉인 후 추가를 거절한다';\n"
                   + "COMMENT ON CONSTRAINT ck_review_record_kind ON public.review_record IS '수동 검수"
                   + " 세 종류와 GRADE만 허용';\n"
                   + "COMMENT ON CONSTRAINT ck_review_record_set ON public.review_record IS 'GRADE는"
                   + " 실제 집합 및 PASS 필수, 수동 검수는 집합 NULL';\n"
                   + "COMMENT ON CONSTRAINT uq_review_record_set ON public.review_record IS '집합당 발급"
                   + " 검수 기록 하나; 수동 NULL은 여러 행 허용';\n"
                   + "COMMENT ON CONSTRAINT fk_review_record_set ON public.review_record IS '검수 기록과"
                   + " 실제 근거 집합의 같은 사본 결속; 즉시 NO ACTION';\n"
                   + "COMMENT ON INDEX public.pk_evidence_set IS '기본 키 제약 소유 무결성 인덱스; 성능 미측정';\n"
                   + "COMMENT ON INDEX public.uq_evidence_set_key IS 'UUID UNIQUE 제약 소유 무결성 인덱스; 성능"
                   + " 미측정';\n"
                   + "COMMENT ON INDEX public.uq_evidence_set_snapshot IS '같은 사본 FK 대상 UNIQUE 제약 소유"
                   + " 인덱스; 성능 미측정';\n"
                   + "COMMENT ON INDEX public.uq_evidence_set_source IS '같은 사본·runtime FK 대상 UNIQUE"
                   + " 제약 소유 인덱스; 성능 미측정';\n"
                   + "COMMENT ON INDEX public.pk_evidence_item IS '집합·BATCH 기본 키 제약 소유 무결성 인덱스; 성능"
                   + " 미측정';\n"
                   + "COMMENT ON INDEX public.uq_review_record_set IS '집합당 검수 기록 UNIQUE 제약 소유 인덱스;"
                   + " 성능 미측정';\n";
    }

    /** V19 LOCAL 회원 인증 원본의 마지막 LF까지 그대로 반환한다. */
    private static String sql19() {
        return "CREATE TABLE public.member_account (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  member_key uuid NOT NULL,\n"
                   + "  state varchar(24) NOT NULL,\n"
                   + "  auth_rev bigint DEFAULT 0 NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  deleted_at timestamptz,\n"
                   + "  CONSTRAINT pk_member_account PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_member_key UNIQUE (member_key),\n"
                   + "  CONSTRAINT ck_member_state CHECK (state IN"
                   + " ('ACTIVE','BLOCKED','DELETED')),\n"
                   + "  CONSTRAINT ck_member_rev CHECK (auth_rev >= 0),\n"
                   + "  CONSTRAINT ck_member_deleted CHECK ((state='DELETED')=(deleted_at IS NOT"
                   + " NULL))\n"
                   + ");\n"
                   + "\n"
                   + "CREATE TABLE public.privacy_policy (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  code varchar(60) NOT NULL,\n"
                   + "  env_code varchar(40) NOT NULL,\n"
                   + "  scope varchar(24) NOT NULL,\n"
                   + "  state varchar(24) NOT NULL,\n"
                   + "  notice_hash char(64) NOT NULL,\n"
                   + "  policy_data jsonb NOT NULL,\n"
                   + "  owner_id bigint NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  CONSTRAINT pk_privacy_policy PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_privacy_policy_code UNIQUE (env_code,code),\n"
                   + "  CONSTRAINT fk_pp_owner FOREIGN KEY (owner_id) REFERENCES"
                   + " public.admin_account(id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT ck_pp_scope CHECK (scope='MEMBER_AUTH'),\n"
                   + "  CONSTRAINT ck_pp_state CHECK (state IN"
                   + " ('DRAFT','ACTIVE','RETIRED','SUSPENDED')),\n"
                   + "  CONSTRAINT ck_pp_code CHECK (code ~ '[^[:space:]]' AND env_code ~"
                   + " '[^[:space:]]'),\n"
                   + "  CONSTRAINT ck_privacy_policy_notice_hash CHECK (notice_hash ~"
                   + " '^[0-9a-f]{64}$'),\n"
                   + "  CONSTRAINT ck_privacy_policy_policy_data CHECK"
                   + " (jsonb_typeof(policy_data)='object' AND"
                   + " octet_length(policy_data::text)<=131072)\n"
                   + ");\n"
                   + "CREATE UNIQUE INDEX uk_pp_active ON public.privacy_policy(env_code,scope)"
                   + " WHERE state='ACTIVE';\n"
                   + "\n"
                   + "CREATE TABLE public.member_profile (\n"
                   + "  member_id bigint NOT NULL,\n"
                   + "  nickname_cipher bytea NOT NULL,\n"
                   + "  policy_id bigint NOT NULL,\n"
                   + "  accepted_at timestamptz NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  CONSTRAINT pk_member_profile PRIMARY KEY (member_id),\n"
                   + "  CONSTRAINT fk_mp_member FOREIGN KEY (member_id) REFERENCES"
                   + " public.member_account(id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT fk_mp_policy FOREIGN KEY (policy_id) REFERENCES"
                   + " public.privacy_policy(id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT ck_member_profile_nickname_cipher CHECK"
                   + " (octet_length(nickname_cipher) BETWEEN 32 AND 524288)\n"
                   + ");\n"
                   + "\n"
                   + "CREATE TABLE public.member_identity (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  identity_key uuid NOT NULL,\n"
                   + "  member_id bigint NOT NULL,\n"
                   + "  provider varchar(24) NOT NULL,\n"
                   + "  realm varchar(160) NOT NULL,\n"
                   + "  lookup_hash char(64) NOT NULL,\n"
                   + "  lookup_ver smallint DEFAULT 1 NOT NULL,\n"
                   + "  subject_cipher bytea NOT NULL,\n"
                   + "  password_hash varchar(512) NOT NULL,\n"
                   + "  active_yn boolean DEFAULT true NOT NULL,\n"
                   + "  proof_at timestamptz NOT NULL,\n"
                   + "  bound_at timestamptz NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  updated_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  CONSTRAINT pk_member_identity PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_mi_key UNIQUE (identity_key),\n"
                   + "  CONSTRAINT uk_mi_member_id UNIQUE (member_id,id),\n"
                   + "  CONSTRAINT fk_mi_member FOREIGN KEY (member_id) REFERENCES"
                   + " public.member_account(id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT ck_mi_provider CHECK (provider='LOCAL' AND realm='LOCAL'),\n"
                   + "  CONSTRAINT ck_mi_ver CHECK (lookup_ver=1),\n"
                   + "  CONSTRAINT ck_mi_password CHECK (password_hash ~ '[^[:space:]]'),\n"
                   + "  CONSTRAINT ck_member_identity_lookup_hash CHECK (lookup_hash ~"
                   + " '^[0-9a-f]{64}$'),\n"
                   + "  CONSTRAINT ck_member_identity_subject_cipher CHECK"
                   + " (octet_length(subject_cipher) BETWEEN 32 AND 524288)\n"
                   + ");\n"
                   + "CREATE UNIQUE INDEX uk_mi_subject ON"
                   + " public.member_identity(provider,realm,lookup_hash) WHERE active_yn;\n"
                   + "CREATE UNIQUE INDEX uk_mi_member_provider ON"
                   + " public.member_identity(member_id,provider) WHERE active_yn;\n"
                   + "\n"
                   + "CREATE TABLE public.member_session (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  session_key uuid NOT NULL,\n"
                   + "  member_id bigint NOT NULL,\n"
                   + "  identity_id bigint NOT NULL,\n"
                   + "  auth_rev bigint NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  last_refresh_at timestamptz NOT NULL,\n"
                   + "  idle_until timestamptz NOT NULL,\n"
                   + "  absolute_until timestamptz NOT NULL,\n"
                   + "  revoked_at timestamptz,\n"
                   + "  revoke_code varchar(40),\n"
                   + "  CONSTRAINT pk_member_session PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_ms_key UNIQUE (session_key),\n"
                   + "  CONSTRAINT uk_ms_member_id UNIQUE (member_id,id),\n"
                   + "  CONSTRAINT fk_ms_member FOREIGN KEY (member_id) REFERENCES"
                   + " public.member_account(id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT fk_ms_identity FOREIGN KEY (member_id,identity_id) REFERENCES"
                   + " public.member_identity(member_id,id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT ck_ms_clock CHECK (auth_rev>=0 AND last_refresh_at>=created_at"
                   + " AND idle_until>last_refresh_at AND idle_until<=absolute_until AND"
                   + " absolute_until=created_at+interval '2160 hours' AND"
                   + " idle_until=LEAST(last_refresh_at+interval '720 hours',absolute_until)),\n"
                   + "  CONSTRAINT ck_ms_revoke CHECK ((revoked_at IS NULL)=(revoke_code IS NULL)"
                   + " AND (revoke_code IS NULL OR revoke_code ~ '^[A-Z0-9_]{1,40}$'))\n"
                   + ");\n"
                   + "\n"
                   + "CREATE TABLE public.member_token (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  session_id bigint NOT NULL,\n"
                   + "  kind varchar(24) NOT NULL,\n"
                   + "  generation bigint NOT NULL,\n"
                   + "  token_hash char(64) NOT NULL,\n"
                   + "  state varchar(24) NOT NULL,\n"
                   + "  issued_at timestamptz NOT NULL,\n"
                   + "  expires_at timestamptz NOT NULL,\n"
                   + "  used_at timestamptz,\n"
                   + "  CONSTRAINT pk_member_token PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_mt_hash UNIQUE (token_hash),\n"
                   + "  CONSTRAINT uk_mt_generation UNIQUE (session_id,kind,generation),\n"
                   + "  CONSTRAINT fk_mt_session FOREIGN KEY (session_id) REFERENCES"
                   + " public.member_session(id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT ck_mt_kind CHECK (kind IN ('ACCESS','REFRESH')),\n"
                   + "  CONSTRAINT ck_mt_state CHECK (state IN ('ISSUED','USED','REVOKED')),\n"
                   + "  CONSTRAINT ck_mt_time CHECK (generation>=0 AND expires_at>issued_at AND"
                   + " expires_at<=issued_at+CASE WHEN kind='ACCESS' THEN interval '5 minutes' ELSE"
                   + " interval '720 hours' END),\n"
                   + "  CONSTRAINT ck_mt_used CHECK ((state='USED' AND kind='REFRESH' AND used_at"
                   + " IS NOT NULL AND used_at>=issued_at AND used_at<expires_at) OR (state<>'USED'"
                   + " AND used_at IS NULL)),\n"
                   + "  CONSTRAINT ck_member_token_token_hash CHECK (token_hash ~"
                   + " '^[0-9a-f]{64}$')\n"
                   + ");\n"
                   + "CREATE UNIQUE INDEX uk_mt_current_refresh ON public.member_token(session_id)"
                   + " WHERE kind='REFRESH' AND state='ISSUED';\n"
                   + "\n"
                   + "CREATE TABLE public.member_flow (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  flow_key uuid NOT NULL,\n"
                   + "  binder_hash char(64) NOT NULL,\n"
                   + "  purpose varchar(24) NOT NULL,\n"
                   + "  state varchar(24) NOT NULL,\n"
                   + "  provider varchar(24) NOT NULL,\n"
                   + "  lookup_hash char(64),\n"
                   + "  code_hash char(64),\n"
                   + "  proof_cipher bytea,\n"
                   + "  attempt_count smallint DEFAULT 0 NOT NULL,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  expires_at timestamptz NOT NULL,\n"
                   + "  verified_at timestamptz,\n"
                   + "  consumed_at timestamptz,\n"
                   + "  CONSTRAINT pk_member_flow PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_mf_key UNIQUE (flow_key),\n"
                   + "  CONSTRAINT ck_mf_purpose CHECK (purpose='SIGNUP'),\n"
                   + "  CONSTRAINT ck_mf_provider CHECK (provider='LOCAL'),\n"
                   + "  CONSTRAINT ck_mf_state CHECK (state IN"
                   + " ('PENDING','VERIFIED','CONSUMED','FAILED')),\n"
                   + "  CONSTRAINT ck_mf_clock CHECK (attempt_count BETWEEN 0 AND 5 AND"
                   + " expires_at=created_at+interval '10 minutes'),\n"
                   + "  CONSTRAINT ck_mf_consume CHECK ((state='CONSUMED')=(consumed_at IS NOT"
                   + " NULL)),\n"
                   + "  CONSTRAINT ck_mf_shape CHECK (\n"
                   + "    (state='PENDING' AND verified_at IS NULL AND consumed_at IS NULL AND"
                   + " attempt_count<5 AND lookup_hash IS NOT NULL AND code_hash IS NOT NULL AND"
                   + " proof_cipher IS NOT NULL) OR\n"
                   + "    (state='VERIFIED' AND verified_at IS NOT NULL AND consumed_at IS NULL AND"
                   + " attempt_count<5 AND lookup_hash IS NOT NULL AND code_hash IS NULL AND"
                   + " proof_cipher IS NOT NULL) OR\n"
                   + "    (state='CONSUMED' AND verified_at IS NOT NULL AND consumed_at IS NOT NULL"
                   + " AND lookup_hash IS NULL AND code_hash IS NULL AND proof_cipher IS NULL) OR\n"
                   + "    (state='FAILED' AND consumed_at IS NULL AND lookup_hash IS NULL AND"
                   + " code_hash IS NULL AND proof_cipher IS NULL)),\n"
                   + "  CONSTRAINT ck_mf_chronology CHECK ((verified_at IS NULL OR"
                   + " (verified_at>=created_at AND verified_at<expires_at)) AND (consumed_at IS"
                   + " NULL OR (verified_at IS NOT NULL AND consumed_at>=verified_at AND"
                   + " consumed_at<expires_at))),\n"
                   + "  CONSTRAINT ck_member_flow_binder_hash CHECK (binder_hash ~"
                   + " '^[0-9a-f]{64}$'),\n"
                   + "  CONSTRAINT ck_member_flow_lookup_hash CHECK (lookup_hash IS NULL OR"
                   + " lookup_hash ~ '^[0-9a-f]{64}$'),\n"
                   + "  CONSTRAINT ck_member_flow_code_hash CHECK (code_hash IS NULL OR code_hash ~"
                   + " '^[0-9a-f]{64}$'),\n"
                   + "  CONSTRAINT ck_member_flow_proof_cipher CHECK (proof_cipher IS NULL OR"
                   + " octet_length(proof_cipher) BETWEEN 32 AND 524288)\n"
                   + ");\n"
                   + "CREATE INDEX ix_mf_expiry ON public.member_flow(expires_at,id);\n"
                   + "\n"
                   + "CREATE TABLE public.member_auth_audit (\n"
                   + "  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,\n"
                   + "  event_key uuid NOT NULL,\n"
                   + "  member_id bigint,\n"
                   + "  request_id uuid NOT NULL,\n"
                   + "  action varchar(40) NOT NULL,\n"
                   + "  result_code varchar(40) NOT NULL,\n"
                   + "  http_status smallint,\n"
                   + "  created_at timestamptz DEFAULT now() NOT NULL,\n"
                   + "  purge_at timestamptz NOT NULL,\n"
                   + "  auth_rev bigint,\n"
                   + "  CONSTRAINT pk_member_auth_audit PRIMARY KEY (id),\n"
                   + "  CONSTRAINT uk_maa_event UNIQUE (event_key),\n"
                   + "  CONSTRAINT fk_maa_member FOREIGN KEY (member_id) REFERENCES"
                   + " public.member_account(id) NOT DEFERRABLE,\n"
                   + "  CONSTRAINT ck_maa_status CHECK (http_status IS NULL OR http_status BETWEEN"
                   + " 100 AND 599),\n"
                   + "  CONSTRAINT ck_maa_purge CHECK (purge_at>created_at AND"
                   + " purge_at<=created_at+interval '2160 hours'),\n"
                   + "  CONSTRAINT ck_maa_rev CHECK ((member_id IS NULL AND auth_rev IS NULL) OR"
                   + " (member_id IS NOT NULL AND auth_rev IS NOT NULL AND auth_rev>=0)),\n"
                   + "  CONSTRAINT ck_maa_action CHECK (action IN"
                   + " ('SIGNUP_START','EMAIL_VERIFY','SIGNUP_COMPLETE','LOGIN_LOCAL','REFRESH','LOGOUT','ME_READ')),\n"
                   + "  CONSTRAINT ck_maa_result CHECK (result_code IN"
                   + " ('STARTED','VERIFIED','CREATED','AUTHENTICATED','ROTATED','LOGGED_OUT','DENIED','RATE_LIMITED','FLOW_EXPIRED','FLOW_LOCKED','IDENTITY_CONFLICT','REFRESH_REUSED','DEPENDENCY_UNAVAILABLE'))\n"
                   + ");\n"
                   + "\n"
                   + "CREATE TABLE public.member_auth_limit (\n"
                   + "  scope varchar(40) NOT NULL,\n"
                   + "  bucket_hash char(64) NOT NULL,\n"
                   + "  window_at timestamptz NOT NULL,\n"
                   + "  hit_count integer NOT NULL,\n"
                   + "  blocked_until timestamptz,\n"
                   + "  purge_at timestamptz NOT NULL,\n"
                   + "  CONSTRAINT pk_member_auth_limit PRIMARY KEY (scope,bucket_hash),\n"
                   + "  CONSTRAINT ck_mal_scope CHECK (scope IN"
                   + " ('SIGNUP_EMAIL_15M','SIGNUP_EMAIL_24H','SIGNUP_SOURCE_15M','LOGIN_EMAIL_15M','LOGIN_SOURCE_15M','VERIFY_SOURCE_15M')),\n"
                   + "  CONSTRAINT ck_mal_hits CHECK (hit_count>=0),\n"
                   + "  CONSTRAINT ck_member_auth_limit_bucket_hash CHECK (bucket_hash ~"
                   + " '^[0-9a-f]{64}$'),\n"
                   + "  CONSTRAINT ck_mal_clock CHECK (purge_at>window_at AND"
                   + " purge_at<=window_at+interval '24 hours' AND (blocked_until IS NULL OR"
                   + " (blocked_until>=window_at AND blocked_until<=purge_at)))\n"
                   + ");\n"
                   + "\n"
                   + "CREATE FUNCTION public.valid_local_policy_document(data jsonb, environment"
                   + " text) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$\n"
                   + "DECLARE\n"
                   + "  item jsonb;\n"
                   + "  field text;\n"
                   + "  start_at timestamptz;\n"
                   + "  end_at timestamptz;\n"
                   + "  evidence_start timestamptz;\n"
                   + "  evidence_end timestamptz;\n"
                   + "BEGIN\n"
                   + "  IF data IS NULL OR jsonb_typeof(data) IS DISTINCT FROM 'object'\n"
                   + "    OR NOT data ?&"
                   + " ARRAY['formatNo','notice','validFrom','validUntil','authProviders','retention','evidence']\n"
                   + "    OR data -"
                   + " ARRAY['formatNo','notice','validFrom','validUntil','authProviders','retention','evidence']"
                   + " <> '{}'::jsonb\n"
                   + "    OR data->'formatNo' IS DISTINCT FROM '1'::jsonb OR"
                   + " (data->'formatNo')::text IS DISTINCT FROM '1'\n"
                   + "    OR data->'authProviders' IS DISTINCT FROM '[\"LOCAL\"]'::jsonb THEN"
                   + " RETURN false; END IF;\n"
                   + "  item := data->'notice';\n"
                   + "  IF jsonb_typeof(item) IS DISTINCT FROM 'object'\n"
                   + "    OR NOT item ?& ARRAY['version','body','contact']\n"
                   + "    OR item - ARRAY['version','body','contact'] <> '{}'::jsonb THEN RETURN"
                   + " false; END IF;\n"
                   + "  FOREACH field IN ARRAY ARRAY['version','body','contact'] LOOP\n"
                   + "    IF jsonb_typeof(item->field) IS DISTINCT FROM 'string' OR NOT"
                   + " (item->>field ~ '[^[:space:]]')\n"
                   + "      OR (field='body' AND (octet_length(item->>field)>32768 OR"
                   + " replace(item->>field,chr(10),'') ~ '[[:cntrl:]]'))\n"
                   + "      OR (field='contact' AND (octet_length(item->>field)>512 OR item->>field"
                   + " ~ '[[:cntrl:]]'))\n"
                   + "      OR (field='version' AND (char_length(item->>field)>60 OR item->>field ~"
                   + " '[[:cntrl:]]'))\n"
                   + "      OR item->>field IS DISTINCT FROM normalize(item->>field, NFC) THEN"
                   + " RETURN false; END IF;\n"
                   + "  END LOOP;\n"
                   + "  FOREACH field IN ARRAY ARRAY['validFrom','validUntil'] LOOP\n"
                   + "    IF jsonb_typeof(data->field) IS DISTINCT FROM 'string'\n"
                   + "      OR NOT (data->>field ~"
                   + " '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$')"
                   + " THEN RETURN false; END IF;\n"
                   + "  END LOOP;\n"
                   + "  start_at := (data->>'validFrom')::timestamptz;\n"
                   + "  end_at := (data->>'validUntil')::timestamptz;\n"
                   + "  IF start_at>=end_at THEN RETURN false; END IF;\n"
                   + "  IF data->'retention' IS DISTINCT FROM"
                   + " '{\"flowTtlSeconds\":600,\"flowCleanupGraceSeconds\":3600,\"accessTtlSeconds\":300,\"accessCleanupGraceSeconds\":3600,\"refreshIdleSeconds\":2592000,\"sessionAbsoluteSeconds\":7776000,\"familyCleanupGraceSeconds\":86400,\"limitMaxSeconds\":86400,\"accessHistoryMaxSeconds\":2592000,\"securityAuditMaxSeconds\":7776000,\"backupMaxSeconds\":3024000}'::jsonb"
                   + " THEN RETURN false; END IF;\n"
                   + "  FOR item IN SELECT value FROM jsonb_each(data->'retention') LOOP\n"
                   + "    IF item::text !~ '^[0-9]+$' THEN RETURN false; END IF;\n"
                   + "  END LOOP;\n"
                   + "  item := data->'evidence';\n"
                   + "  IF jsonb_typeof(item) IS DISTINCT FROM 'object'\n"
                   + "    OR NOT item ?&"
                   + " ARRAY['responsibility','access','keys','processors','copies','verification']\n"
                   + "    OR item -"
                   + " ARRAY['responsibility','access','keys','processors','copies','verification']"
                   + " <> '{}'::jsonb THEN RETURN false; END IF;\n"
                   + "  FOR item IN SELECT value FROM jsonb_each(item) LOOP\n"
                   + "    IF jsonb_typeof(item) IS DISTINCT FROM 'object'\n"
                   + "      OR NOT item ?&"
                   + " ARRAY['ref','sha256','envCode','scope','verifiedAt','validUntil']\n"
                   + "      OR item -"
                   + " ARRAY['ref','sha256','envCode','scope','verifiedAt','validUntil'] <>"
                   + " '{}'::jsonb THEN RETURN false; END IF;\n"
                   + "    FOREACH field IN ARRAY"
                   + " ARRAY['ref','sha256','envCode','scope','verifiedAt','validUntil'] LOOP\n"
                   + "      IF jsonb_typeof(item->field) IS DISTINCT FROM 'string' THEN RETURN"
                   + " false; END IF;\n"
                   + "    END LOOP;\n"
                   + "    IF NOT (item->>'ref' ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$')\n"
                   + "      OR NOT (item->>'sha256' ~ '^[0-9a-f]{64}$')\n"
                   + "      OR item->>'envCode' IS DISTINCT FROM environment OR item->>'scope' IS"
                   + " DISTINCT FROM 'MEMBER_AUTH' THEN RETURN false; END IF;\n"
                   + "    FOREACH field IN ARRAY ARRAY['verifiedAt','validUntil'] LOOP\n"
                   + "      IF NOT (item->>field ~"
                   + " '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$')"
                   + " THEN RETURN false; END IF;\n"
                   + "    END LOOP;\n"
                   + "    evidence_start := (item->>'verifiedAt')::timestamptz;\n"
                   + "    evidence_end := (item->>'validUntil')::timestamptz;\n"
                   + "    IF evidence_start>=evidence_end OR evidence_start>start_at OR"
                   + " evidence_end<end_at THEN RETURN false; END IF;\n"
                   + "  END LOOP;\n"
                   + "  RETURN true;\n"
                   + "EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN\n"
                   + "  RETURN false;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "ALTER TABLE public.privacy_policy ADD CONSTRAINT ck_pp_local_document CHECK"
                   + " (public.valid_local_policy_document(policy_data,env_code) IS TRUE);\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_local_policy() RETURNS trigger LANGUAGE plpgsql"
                   + " AS $$\n"
                   + "BEGIN\n"
                   + "  IF TG_OP IN ('DELETE','TRUNCATE') THEN\n"
                   + "    RAISE EXCEPTION 'privacy_policy immutable' USING ERRCODE='23514';\n"
                   + "  END IF;\n"
                   + "  IF TG_OP='INSERT' THEN\n"
                   + "    IF NEW.state IS DISTINCT FROM 'DRAFT' THEN\n"
                   + "      RAISE EXCEPTION 'privacy_policy DRAFT insert only' USING"
                   + " ERRCODE='23514';\n"
                   + "    END IF;\n"
                   + "  ELSE\n"
                   + "    IF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state') OR"
                   + " NOT (\n"
                   + "      NEW.state=OLD.state OR (OLD.state='DRAFT' AND NEW.state IN"
                   + " ('ACTIVE','RETIRED')) OR\n"
                   + "      (OLD.state='ACTIVE' AND NEW.state IN ('SUSPENDED','RETIRED')) OR\n"
                   + "      (OLD.state='SUSPENDED' AND NEW.state='RETIRED')) THEN\n"
                   + "      RAISE EXCEPTION 'privacy_policy immutable transition' USING"
                   + " ERRCODE='23514';\n"
                   + "    END IF;\n"
                   + "  END IF;\n"
                   + "  IF NEW.state='ACTIVE' THEN\n"
                   + "    PERFORM id FROM public.admin_account WHERE id=NEW.owner_id AND active_yn"
                   + " FOR SHARE;\n"
                   + "    IF NOT FOUND THEN\n"
                   + "      RAISE EXCEPTION 'privacy_policy active owner required' USING"
                   + " ERRCODE='23514';\n"
                   + "    END IF;\n"
                   + "  END IF;\n"
                   + "  RETURN NEW;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "CREATE TRIGGER trg_local_policy_row BEFORE INSERT OR UPDATE OR DELETE ON"
                   + " public.privacy_policy FOR EACH ROW EXECUTE FUNCTION"
                   + " public.guard_local_policy();\n"
                   + "CREATE TRIGGER trg_local_policy_truncate BEFORE TRUNCATE ON"
                   + " public.privacy_policy FOR EACH STATEMENT EXECUTE FUNCTION"
                   + " public.guard_local_policy();\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_local_profile_consent() RETURNS trigger LANGUAGE"
                   + " plpgsql AS $$\n"
                   + "BEGIN\n"
                   + "  IF ROW(NEW.member_id,NEW.policy_id,NEW.accepted_at,NEW.created_at) IS"
                   + " DISTINCT FROM"
                   + " ROW(OLD.member_id,OLD.policy_id,OLD.accepted_at,OLD.created_at) THEN\n"
                   + "    RAISE EXCEPTION 'member_profile consent immutable' USING"
                   + " ERRCODE='23514';\n"
                   + "  END IF;\n"
                   + "  RETURN NEW;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "CREATE TRIGGER trg_local_profile_consent BEFORE UPDATE ON"
                   + " public.member_profile FOR EACH ROW EXECUTE FUNCTION"
                   + " public.guard_local_profile_consent();\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_local_session() RETURNS trigger LANGUAGE plpgsql"
                   + " AS $$\n"
                   + "BEGIN\n"
                   + "  IF ROW(NEW.id,NEW.session_key,NEW.member_id,NEW.identity_id,NEW.auth_rev,NEW.created_at,NEW.absolute_until)"
                   + " IS DISTINCT FROM"
                   + " ROW(OLD.id,OLD.session_key,OLD.member_id,OLD.identity_id,OLD.auth_rev,OLD.created_at,OLD.absolute_until)\n"
                   + "    OR NEW.last_refresh_at<OLD.last_refresh_at\n"
                   + "    OR (NEW.revoked_at IS NOT NULL AND"
                   + " ROW(NEW.last_refresh_at,NEW.idle_until) IS DISTINCT FROM"
                   + " ROW(OLD.last_refresh_at,OLD.idle_until))\n"
                   + "    OR (OLD.revoked_at IS NOT NULL AND"
                   + " ROW(NEW.revoked_at,NEW.revoke_code,NEW.last_refresh_at,NEW.idle_until) IS"
                   + " DISTINCT FROM"
                   + " ROW(OLD.revoked_at,OLD.revoke_code,OLD.last_refresh_at,OLD.idle_until))"
                   + " THEN\n"
                   + "    RAISE EXCEPTION 'member_session stable family and first revocation' USING"
                   + " ERRCODE='23514';\n"
                   + "  END IF;\n"
                   + "  RETURN NEW;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "CREATE TRIGGER trg_local_session BEFORE UPDATE ON public.member_session FOR"
                   + " EACH ROW EXECUTE FUNCTION public.guard_local_session();\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_local_token() RETURNS trigger LANGUAGE plpgsql"
                   + " AS $$\n"
                   + "BEGIN\n"
                   + "  IF ROW(NEW.id,NEW.session_id,NEW.kind,NEW.generation,NEW.token_hash,NEW.issued_at,NEW.expires_at)"
                   + " IS DISTINCT FROM"
                   + " ROW(OLD.id,OLD.session_id,OLD.kind,OLD.generation,OLD.token_hash,OLD.issued_at,OLD.expires_at)\n"
                   + "    OR (OLD.state<>'ISSUED' AND ROW(NEW.state,NEW.used_at) IS DISTINCT FROM"
                   + " ROW(OLD.state,OLD.used_at)) THEN\n"
                   + "    RAISE EXCEPTION 'member_token immutable issuance and terminal evidence'"
                   + " USING ERRCODE='23514';\n"
                   + "  END IF;\n"
                   + "  RETURN NEW;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "CREATE TRIGGER trg_local_token BEFORE UPDATE ON public.member_token FOR EACH"
                   + " ROW EXECUTE FUNCTION public.guard_local_token();\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_local_signup_flow() RETURNS trigger LANGUAGE"
                   + " plpgsql AS $$\n"
                   + "BEGIN\n"
                   + "  IF ROW(NEW.id,NEW.flow_key,NEW.binder_hash,NEW.purpose,NEW.provider,NEW.created_at,NEW.expires_at)"
                   + " IS DISTINCT FROM"
                   + " ROW(OLD.id,OLD.flow_key,OLD.binder_hash,OLD.purpose,OLD.provider,OLD.created_at,OLD.expires_at)\n"
                   + "    OR NEW.attempt_count<OLD.attempt_count\n"
                   + "    OR NOT (NEW.state=OLD.state OR (OLD.state='PENDING' AND NEW.state IN"
                   + " ('VERIFIED','FAILED')) OR (OLD.state='VERIFIED' AND NEW.state IN"
                   + " ('CONSUMED','FAILED')))\n"
                   + "    OR (OLD.verified_at IS NOT NULL AND NEW.verified_at IS DISTINCT FROM"
                   + " OLD.verified_at)\n"
                   + "    OR (OLD.verified_at IS NULL AND NEW.verified_at IS NOT NULL AND NOT"
                   + " (OLD.state='PENDING' AND NEW.state='VERIFIED'))\n"
                   + "    OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM"
                   + " OLD.consumed_at)\n"
                   + "    OR (NEW.lookup_hash IS NOT NULL AND NEW.lookup_hash IS DISTINCT FROM"
                   + " OLD.lookup_hash)\n"
                   + "    OR (NEW.code_hash IS NOT NULL AND NEW.code_hash IS DISTINCT FROM"
                   + " OLD.code_hash)\n"
                   + "    OR (NEW.proof_cipher IS NOT NULL AND NEW.proof_cipher IS DISTINCT FROM"
                   + " OLD.proof_cipher)\n"
                   + "    OR (OLD.state IN ('FAILED','CONSUMED') AND NEW IS DISTINCT FROM OLD)"
                   + " THEN\n"
                   + "    RAISE EXCEPTION 'member_flow stable signup and monotonic cleanup' USING"
                   + " ERRCODE='23514';\n"
                   + "  END IF;\n"
                   + "  RETURN NEW;\n"
                   + "END;\n"
                   + "$$;\n"
                   + "CREATE TRIGGER trg_local_signup_flow BEFORE UPDATE ON public.member_flow FOR"
                   + " EACH ROW EXECUTE FUNCTION public.guard_local_signup_flow();\n"
                   + "\n"
                   + "CREATE FUNCTION public.guard_local_auth_audit() RETURNS trigger LANGUAGE"
                   + " plpgsql AS $$\n"
                   + "BEGIN\n"
                   + "  RAISE EXCEPTION 'member_auth_audit append only' USING ERRCODE='23514';\n"
                   + "END;\n"
                   + "$$;\n"
                   + "CREATE TRIGGER trg_local_auth_audit_row BEFORE UPDATE OR DELETE ON"
                   + " public.member_auth_audit FOR EACH ROW EXECUTE FUNCTION"
                   + " public.guard_local_auth_audit();\n"
                   + "CREATE TRIGGER trg_local_auth_audit_truncate BEFORE TRUNCATE ON"
                   + " public.member_auth_audit FOR EACH STATEMENT EXECUTE FUNCTION"
                   + " public.guard_local_auth_audit();\n"
                   + "REVOKE ALL ON public.privacy_policy FROM PUBLIC;\n"
                   + "REVOKE UPDATE, DELETE, TRUNCATE ON public.member_auth_audit FROM PUBLIC;\n";
    }

    /** V20 초대 저장 원본의 마지막 LF까지 그대로 반환한다. */
    private static String sql20() {
        return """
        -- 첫 초대 저장 단위. 수집은 운영 정책과 회원 자격이 준비될 때까지 비활성이다.
        ALTER TABLE public.privacy_policy DROP CONSTRAINT ck_pp_scope;
        ALTER TABLE public.privacy_policy ADD CONSTRAINT ck_pp_scope CHECK (scope IN ('MEMBER_AUTH','PLAYTEST'));
        ALTER TABLE public.privacy_policy DROP CONSTRAINT ck_pp_local_document;

        CREATE TABLE public.play_test (
          id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
          test_key uuid NOT NULL,
          version_id bigint NOT NULL,
          snapshot_id bigint NOT NULL,
          runtime_id bigint NOT NULL,
          config_hash char(64) NOT NULL,
          runtime_epoch bigint NOT NULL,
          role_a varchar(32) NOT NULL,
          role_b varchar(32) NOT NULL,
          mode varchar(24) NOT NULL,
          state varchar(24) DEFAULT 'WAITING' NOT NULL,
          outcome varchar(24),
          rev bigint DEFAULT 0 NOT NULL,
          draft_rev bigint DEFAULT 0 NOT NULL,
          invite_until timestamptz NOT NULL,
          ready_until timestamptz,
          started_at timestamptz,
          deadline_at timestamptz,
          ended_at timestamptz,
          result_until timestamptz,
          attempt_count smallint DEFAULT 0 NOT NULL,
          wrong_count smallint DEFAULT 0 NOT NULL,
          final_score smallint,
          created_by bigint NOT NULL,
          created_at timestamptz DEFAULT now() NOT NULL,
          updated_at timestamptz DEFAULT now() NOT NULL,
          cancel_reason varchar(40),
          CONSTRAINT pk_play_test PRIMARY KEY (id),
          CONSTRAINT uk_play_test_key UNIQUE (test_key),
          CONSTRAINT uk_play_test_snapshot UNIQUE (snapshot_id,id),
          CONSTRAINT fk_test_version FOREIGN KEY (version_id) REFERENCES public.story_version(id) NOT DEFERRABLE,
          CONSTRAINT fk_test_snapshot FOREIGN KEY (version_id,snapshot_id) REFERENCES public.review_snapshot(version_id,id) NOT DEFERRABLE,
          CONSTRAINT fk_test_runtime FOREIGN KEY (runtime_id) REFERENCES public.grade_runtime(id) NOT DEFERRABLE,
          CONSTRAINT fk_test_pair FOREIGN KEY (version_id,role_a,role_b) REFERENCES public.story_pair(version_id,role_a,role_b) NOT DEFERRABLE,
          CONSTRAINT fk_test_admin FOREIGN KEY (created_by) REFERENCES public.admin_account(id) NOT DEFERRABLE,
          CONSTRAINT ck_test_mode CHECK (mode IN ('BLIND','FUNCTIONAL')),
          CONSTRAINT ck_test_config_hash CHECK (config_hash ~ '^[0-9a-f]{64}$'),
          CONSTRAINT ck_test_state CHECK (state IN ('WAITING','RUNNING','ENDED','EXPIRED','CANCELLED')),
          CONSTRAINT ck_test_outcome CHECK (outcome IS NULL OR outcome IN ('SUCCESS','ATTEMPTS_EXHAUSTED','TIME_LIMIT','FORFEIT','SYSTEM_ERROR')),
          CONSTRAINT ck_test_counters CHECK (rev>=0 AND draft_rev>=0 AND runtime_epoch>=0 AND attempt_count BETWEEN 0 AND 5 AND wrong_count BETWEEN 0 AND attempt_count),
          CONSTRAINT ck_test_invite_clock CHECK (invite_until=created_at+interval '7 days' AND updated_at>=created_at AND (ready_until IS NULL OR (ready_until>created_at AND ready_until<=invite_until))),
          CONSTRAINT ck_test_start_clock CHECK ((started_at IS NULL AND deadline_at IS NULL) OR (started_at IS NOT NULL AND deadline_at IS NOT NULL AND deadline_at>started_at)),
          CONSTRAINT ck_test_end CHECK ((state='ENDED')=(outcome IS NOT NULL) AND (state IN ('ENDED','EXPIRED','CANCELLED'))=(ended_at IS NOT NULL) AND (ended_at IS NULL OR ended_at>=created_at)),
          CONSTRAINT ck_test_score CHECK ((state='ENDED' AND outcome IN ('SUCCESS','ATTEMPTS_EXHAUSTED','TIME_LIMIT') AND final_score BETWEEN 0 AND 100 AND final_score IS NOT NULL) OR (final_score IS NULL AND NOT (state='ENDED' AND outcome IN ('SUCCESS','ATTEMPTS_EXHAUSTED','TIME_LIMIT')))),
          CONSTRAINT ck_test_result_clock CHECK ((result_until IS NULL AND ended_at IS NULL) OR (result_until IS NOT NULL AND ended_at IS NOT NULL AND result_until=ended_at+interval '24 hours') OR (result_until IS NULL AND ended_at IS NOT NULL)),
          CONSTRAINT ck_test_cancel_reason CHECK (cancel_reason IS NULL OR cancel_reason IN ('TESTER_REQUEST','ACCESS_REVOKED','CONTENT_REVIEW','OPERATIONAL'))
        );

        CREATE TABLE public.test_member (
          test_id bigint NOT NULL,
          member_id bigint NOT NULL,
          slot smallint NOT NULL,
          invite_gen integer DEFAULT 1 NOT NULL,
          invite_state varchar(24) DEFAULT 'SENT' NOT NULL,
          accepted_at timestamptz,
          accepted_policy_id bigint,
          accepted_notice_hash char(64),
          ready_yn boolean DEFAULT false NOT NULL,
          role_code varchar(32),
          last_seen_at timestamptz,
          blind_declared boolean DEFAULT false NOT NULL,
          revoked_at timestamptz,
          created_at timestamptz DEFAULT now() NOT NULL,
          updated_at timestamptz DEFAULT now() NOT NULL,
          CONSTRAINT pk_test_member PRIMARY KEY (test_id,member_id),
          CONSTRAINT uk_test_member_slot UNIQUE (test_id,slot),
          CONSTRAINT uk_test_member_role UNIQUE (test_id,role_code),
          CONSTRAINT fk_tm_test FOREIGN KEY (test_id) REFERENCES public.play_test(id) NOT DEFERRABLE,
          CONSTRAINT fk_tm_member FOREIGN KEY (member_id) REFERENCES public.member_account(id) NOT DEFERRABLE,
          CONSTRAINT fk_tm_policy FOREIGN KEY (accepted_policy_id) REFERENCES public.privacy_policy(id) NOT DEFERRABLE,
          CONSTRAINT ck_tm_slot CHECK (slot IN (1,2) AND invite_gen>=1),
          CONSTRAINT ck_tm_invite_state CHECK (invite_state IN ('SENT','ACCEPTED','DECLINED','REVOKED')),
          CONSTRAINT ck_tm_accept CHECK ((accepted_at IS NOT NULL)=(accepted_policy_id IS NOT NULL) AND (accepted_at IS NOT NULL)=(accepted_notice_hash IS NOT NULL) AND (invite_state='ACCEPTED' OR accepted_at IS NULL OR invite_state='REVOKED') AND (invite_state<>'ACCEPTED' OR accepted_at IS NOT NULL) AND (NOT ready_yn OR invite_state='ACCEPTED') AND (invite_state='REVOKED')=(revoked_at IS NOT NULL)),
          CONSTRAINT ck_tm_notice_hash CHECK (accepted_notice_hash IS NULL OR accepted_notice_hash ~ '^[0-9a-f]{64}$'),
          CONSTRAINT ck_tm_clock CHECK (updated_at>=created_at AND (accepted_at IS NULL OR accepted_at>=created_at) AND (revoked_at IS NULL OR revoked_at>=created_at))
        );
        CREATE INDEX ix_tm_member_invite ON public.test_member(member_id,invite_state,test_id);

        -- A prior material view permanently disqualifies this member from BLIND for the story.
        -- Creator/reviewer administrator access is a separate authorization check, not a member exposure.
        CREATE TABLE public.test_exposure (
          story_id bigint NOT NULL,
          member_id bigint NOT NULL,
          first_exposed_at timestamptz DEFAULT now() NOT NULL,
          CONSTRAINT pk_test_exposure PRIMARY KEY (story_id,member_id),
          CONSTRAINT fk_tex_story FOREIGN KEY (story_id) REFERENCES public.story(id) NOT DEFERRABLE,
          CONSTRAINT fk_tex_member FOREIGN KEY (member_id) REFERENCES public.member_account(id) NOT DEFERRABLE
        );

        CREATE TABLE public.test_retention (
          test_id bigint NOT NULL,
          policy_id bigint NOT NULL,
          raw_until timestamptz,
          max_until timestamptz,
          selected_at timestamptz,
          CONSTRAINT pk_test_retention PRIMARY KEY (test_id),
          CONSTRAINT fk_tret_test FOREIGN KEY (test_id) REFERENCES public.play_test(id) NOT DEFERRABLE,
          CONSTRAINT fk_tret_policy FOREIGN KEY (policy_id) REFERENCES public.privacy_policy(id) NOT DEFERRABLE,
          CONSTRAINT ck_tret_clock CHECK ((raw_until IS NULL AND max_until IS NULL AND selected_at IS NULL) OR (raw_until IS NOT NULL AND max_until IS NOT NULL AND raw_until<=max_until AND (selected_at IS NULL OR (selected_at<=raw_until AND raw_until=max_until))))
        );
        CREATE INDEX ix_tret_due ON public.test_retention(raw_until,test_id) WHERE raw_until IS NOT NULL;

        ALTER TABLE public.test_action ALTER COLUMN admin_id DROP NOT NULL;
        ALTER TABLE public.test_action ADD COLUMN member_id bigint;
        ALTER TABLE public.test_action ADD CONSTRAINT fk_ta_member FOREIGN KEY (member_id) REFERENCES public.member_account(id) NOT DEFERRABLE;
        ALTER TABLE public.test_action ADD CONSTRAINT ck_ta_actor CHECK ((admin_id IS NOT NULL) <> (member_id IS NOT NULL));
        ALTER TABLE public.test_audit DROP CONSTRAINT ck_audit_actor_kind;
        ALTER TABLE public.test_audit ADD CONSTRAINT ck_audit_actor_kind CHECK (actor_kind IN ('ADMIN','MEMBER','WORKER','SYSTEM'));

        CREATE FUNCTION public.valid_playtest_policy_document(data jsonb, environment text) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
        DECLARE
          item jsonb;
          field text;
          start_at timestamptz;
          end_at timestamptz;
          evidence_start timestamptz;
          evidence_end timestamptz;
        BEGIN
          IF data IS NULL OR jsonb_typeof(data) IS DISTINCT FROM 'object'
            OR NOT data ?& ARRAY['formatNo','notice','validFrom','validUntil','retention','evidence']
            OR data - ARRAY['formatNo','notice','validFrom','validUntil','retention','evidence'] <> '{}'::jsonb
            OR data->'formatNo' IS DISTINCT FROM '1'::jsonb OR (data->'formatNo')::text IS DISTINCT FROM '1' THEN RETURN false; END IF;
          item := data->'notice';
          IF jsonb_typeof(item) IS DISTINCT FROM 'object'
            OR NOT item ?& ARRAY['version','body','contact']
            OR item - ARRAY['version','body','contact'] <> '{}'::jsonb THEN RETURN false; END IF;
          FOREACH field IN ARRAY ARRAY['version','body','contact'] LOOP
            IF jsonb_typeof(item->field) IS DISTINCT FROM 'string' OR NOT (item->>field ~ '[^[:space:]]')
              OR (field='body' AND (octet_length(item->>field)>32768 OR replace(item->>field,chr(10),'') ~ '[[:cntrl:]]'))
              OR (field='contact' AND (octet_length(item->>field)>512 OR item->>field ~ '[[:cntrl:]]'))
              OR (field='version' AND (char_length(item->>field)>60 OR item->>field ~ '[[:cntrl:]]'))
              OR item->>field IS DISTINCT FROM normalize(item->>field, NFC) THEN RETURN false; END IF;
          END LOOP;
          FOREACH field IN ARRAY ARRAY['validFrom','validUntil'] LOOP
            IF jsonb_typeof(data->field) IS DISTINCT FROM 'string'
              OR NOT (data->>field ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$') THEN RETURN false; END IF;
          END LOOP;
          start_at := (data->>'validFrom')::timestamptz;
          end_at := (data->>'validUntil')::timestamptz;
          IF start_at>=end_at THEN RETURN false; END IF;
          IF data->'retention' IS DISTINCT FROM '{"inviteSeconds":604800,"lobbySeconds":1800,"resultSeconds":86400,"rawSeconds":7776000,"selectedSeconds":31536000,"backupMaxSeconds":3024000}'::jsonb THEN RETURN false; END IF;
          FOR item IN SELECT value FROM jsonb_each(data->'retention') LOOP
            IF item::text !~ '^[0-9]+$' THEN RETURN false; END IF;
          END LOOP;
          item := data->'evidence';
          IF jsonb_typeof(item) IS DISTINCT FROM 'object'
            OR NOT item ?& ARRAY['responsibility','access','keys','processors','copies','verification']
            OR item - ARRAY['responsibility','access','keys','processors','copies','verification'] <> '{}'::jsonb THEN RETURN false; END IF;
          FOR item IN SELECT value FROM jsonb_each(item) LOOP
            IF jsonb_typeof(item) IS DISTINCT FROM 'object'
              OR NOT item ?& ARRAY['ref','sha256','envCode','scope','verifiedAt','validUntil']
              OR item - ARRAY['ref','sha256','envCode','scope','verifiedAt','validUntil'] <> '{}'::jsonb THEN RETURN false; END IF;
            FOREACH field IN ARRAY ARRAY['ref','sha256','envCode','scope','verifiedAt','validUntil'] LOOP
              IF jsonb_typeof(item->field) IS DISTINCT FROM 'string' THEN RETURN false; END IF;
            END LOOP;
            IF NOT (item->>'ref' ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$')
              OR NOT (item->>'sha256' ~ '^[0-9a-f]{64}$')
              OR item->>'envCode' IS DISTINCT FROM environment OR item->>'scope' IS DISTINCT FROM 'PLAYTEST' THEN RETURN false; END IF;
            FOREACH field IN ARRAY ARRAY['verifiedAt','validUntil'] LOOP
              IF NOT (item->>field ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z$') THEN RETURN false; END IF;
            END LOOP;
            evidence_start := (item->>'verifiedAt')::timestamptz;
            evidence_end := (item->>'validUntil')::timestamptz;
            IF evidence_start>=evidence_end OR evidence_start>start_at OR evidence_end<end_at THEN RETURN false; END IF;
          END LOOP;
          RETURN true;
        EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
          RETURN false;
        END;
        $$;

        ALTER TABLE public.privacy_policy ADD CONSTRAINT ck_pp_document CHECK (
          (scope='MEMBER_AUTH' AND public.valid_local_policy_document(policy_data,env_code) IS TRUE)
          OR (scope='PLAYTEST' AND public.valid_playtest_policy_document(policy_data,env_code) IS TRUE)
        );

        CREATE FUNCTION public.guard_playtest_retention_policy() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM public.privacy_policy WHERE id=NEW.policy_id AND scope='PLAYTEST') THEN
            RAISE EXCEPTION 'PLAYTEST policy required' USING ERRCODE='23514';
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER trg_playtest_retention_policy BEFORE INSERT OR UPDATE ON public.test_retention FOR EACH ROW EXECUTE FUNCTION public.guard_playtest_retention_policy();

        CREATE FUNCTION public.guard_playtest_pins() RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
          pinned_hash char(64);
          pinned_epoch bigint;
        BEGIN
          IF TG_OP='INSERT' THEN
            SELECT config_hash,epoch INTO pinned_hash,pinned_epoch FROM public.grade_runtime
              WHERE id=NEW.runtime_id FOR SHARE;
            IF NOT FOUND OR ROW(NEW.config_hash,NEW.runtime_epoch) IS DISTINCT FROM ROW(pinned_hash,pinned_epoch) THEN
              RAISE EXCEPTION 'play_test runtime pin mismatch' USING ERRCODE='23514';
            END IF;
          ELSIF ROW(NEW.id,NEW.test_key,NEW.version_id,NEW.snapshot_id,NEW.runtime_id,NEW.config_hash,NEW.runtime_epoch,NEW.role_a,NEW.role_b,NEW.mode,NEW.created_by,NEW.created_at,NEW.invite_until)
            IS DISTINCT FROM ROW(OLD.id,OLD.test_key,OLD.version_id,OLD.snapshot_id,OLD.runtime_id,OLD.config_hash,OLD.runtime_epoch,OLD.role_a,OLD.role_b,OLD.mode,OLD.created_by,OLD.created_at,OLD.invite_until) THEN
            RAISE EXCEPTION 'play_test pinned provenance immutable' USING ERRCODE='23514';
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER trg_playtest_pins BEFORE INSERT OR UPDATE ON public.play_test FOR EACH ROW EXECUTE FUNCTION public.guard_playtest_pins();

        CREATE FUNCTION public.guard_playtest_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
          policy_hash char(64);
        BEGIN
          IF TG_OP='DELETE' THEN
            IF OLD.accepted_at IS NOT NULL THEN
              RAISE EXCEPTION 'accepted PLAYTEST consent cannot be deleted' USING ERRCODE='23514';
            END IF;
            RETURN OLD;
          ELSIF TG_OP='UPDATE' THEN
            IF ROW(NEW.test_id,NEW.member_id,NEW.slot,NEW.created_at) IS DISTINCT FROM ROW(OLD.test_id,OLD.member_id,OLD.slot,OLD.created_at)
              OR (OLD.accepted_at IS NOT NULL AND ROW(NEW.accepted_at,NEW.accepted_policy_id,NEW.accepted_notice_hash) IS DISTINCT FROM ROW(OLD.accepted_at,OLD.accepted_policy_id,OLD.accepted_notice_hash)) THEN
              RAISE EXCEPTION 'test_member identity and consent immutable' USING ERRCODE='23514';
            END IF;
            IF OLD.accepted_at IS NOT NULL OR NEW.accepted_at IS NULL THEN RETURN NEW; END IF;
          ELSIF NEW.accepted_at IS NULL THEN
            RETURN NEW;
          END IF;
          SELECT notice_hash INTO policy_hash FROM public.privacy_policy
            WHERE id=NEW.accepted_policy_id AND scope='PLAYTEST' AND state='ACTIVE' FOR SHARE;
          IF NOT FOUND OR policy_hash IS DISTINCT FROM NEW.accepted_notice_hash THEN
            RAISE EXCEPTION 'active PLAYTEST notice required at acceptance' USING ERRCODE='23514';
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER trg_playtest_acceptance BEFORE INSERT OR UPDATE OR DELETE ON public.test_member FOR EACH ROW EXECUTE FUNCTION public.guard_playtest_acceptance();

        CREATE FUNCTION public.guard_test_exposure() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'test_exposure is append only' USING ERRCODE='23514';
        END;
        $$;
        CREATE TRIGGER trg_test_exposure_row BEFORE UPDATE OR DELETE ON public.test_exposure FOR EACH ROW EXECUTE FUNCTION public.guard_test_exposure();
        CREATE TRIGGER trg_test_exposure_truncate BEFORE TRUNCATE ON public.test_exposure FOR EACH STATEMENT EXECUTE FUNCTION public.guard_test_exposure();
        REVOKE ALL ON public.play_test, public.test_member, public.test_exposure, public.test_retention FROM PUBLIC;
        REVOKE ALL ON FUNCTION public.valid_playtest_policy_document(jsonb,text), public.guard_playtest_retention_policy(), public.guard_playtest_pins(), public.guard_playtest_acceptance(), public.guard_test_exposure() FROM PUBLIC;
        """;
    }

    /** V21 회원별 힌트 열람 원본의 마지막 LF까지 그대로 반환한다. */
    private static String sql21() {
        return """
        CREATE TABLE public.test_hint (
          test_id bigint NOT NULL,
          member_id bigint NOT NULL,
          level smallint NOT NULL,
          opened_at timestamptz DEFAULT now() NOT NULL,
          CONSTRAINT pk_test_hint PRIMARY KEY (test_id,member_id,level),
          CONSTRAINT fk_hint_member FOREIGN KEY (test_id,member_id) REFERENCES public.test_member(test_id,member_id) NOT DEFERRABLE,
          CONSTRAINT ck_hint_level CHECK (level BETWEEN 1 AND 3)
        );
        """;
    }

    /** V22 공동 보고서 저장 원본의 마지막 LF까지 그대로 반환한다. */
    private static String sql22() {
        return """
        -- H5 공동 보고서 저장 구조. 접수·권한·암호화/AAD·canonical 해시 검사는 서비스 책임이다.
        ALTER TABLE public.play_test ADD COLUMN draft_cipher bytea;
        ALTER TABLE public.play_test ADD COLUMN draft_hash char(64);
        ALTER TABLE public.play_test ADD COLUMN next_submit_no integer DEFAULT 1 NOT NULL;
        ALTER TABLE public.play_test ADD CONSTRAINT ck_test_draft_cipher CHECK (draft_cipher IS NULL OR octet_length(draft_cipher) BETWEEN 32 AND 524288);
        ALTER TABLE public.play_test ADD CONSTRAINT ck_test_draft_hash CHECK (draft_hash IS NULL OR draft_hash ~ '^[0-9a-f]{64}$');
        ALTER TABLE public.play_test ADD CONSTRAINT ck_test_draft_pair CHECK ((draft_cipher IS NULL) = (draft_hash IS NULL));
        ALTER TABLE public.play_test ADD CONSTRAINT ck_test_submit_ordinal CHECK (next_submit_no >= 1);
        ALTER TABLE public.play_test ADD CONSTRAINT uk_test_report_pins UNIQUE (snapshot_id,runtime_id,id);

        ALTER TABLE public.test_member ADD COLUMN feedback_cipher bytea;
        ALTER TABLE public.test_member ADD COLUMN feedback_at timestamptz;
        ALTER TABLE public.test_member ADD CONSTRAINT ck_tm_feedback CHECK ((feedback_cipher IS NULL) = (feedback_at IS NULL) AND (feedback_cipher IS NULL OR octet_length(feedback_cipher) BETWEEN 32 AND 524288));

        CREATE TABLE public.test_report (
          id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
          report_key uuid NOT NULL,
          test_id bigint NOT NULL,
          snapshot_id bigint NOT NULL,
          runtime_id bigint NOT NULL,
          config_hash char(64) NOT NULL,
          runtime_epoch bigint NOT NULL,
          source_draft_rev bigint NOT NULL,
          proposer_id bigint NOT NULL,
          payload_cipher bytea,
          payload_hash char(64),
          state varchar(24) DEFAULT 'PROPOSED' NOT NULL,
          accepted_by bigint,
          accepted_at timestamptz,
          submit_no integer,
          purged_at timestamptz,
          created_at timestamptz DEFAULT now() NOT NULL,
          updated_at timestamptz DEFAULT now() NOT NULL,
          CONSTRAINT pk_test_report PRIMARY KEY (id),
          CONSTRAINT uk_report_key UNIQUE (report_key),
          CONSTRAINT uk_report_pins UNIQUE (snapshot_id,runtime_id,id),
          CONSTRAINT uk_report_submit UNIQUE (test_id,submit_no),
          CONSTRAINT fk_report_test FOREIGN KEY (snapshot_id,runtime_id,test_id) REFERENCES public.play_test(snapshot_id,runtime_id,id) NOT DEFERRABLE,
          CONSTRAINT fk_report_proposer FOREIGN KEY (test_id,proposer_id) REFERENCES public.test_member(test_id,member_id) NOT DEFERRABLE,
          CONSTRAINT fk_report_acceptor FOREIGN KEY (test_id,accepted_by) REFERENCES public.test_member(test_id,member_id) NOT DEFERRABLE,
          CONSTRAINT ck_report_state CHECK (state IN ('PROPOSED','REJECTED','WITHDRAWN','INVALIDATED','ACCEPTED','GRADED','UNGRADABLE','CANCELLED')),
          CONSTRAINT ck_report_hash CHECK (payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$'),
          CONSTRAINT ck_report_config CHECK (config_hash ~ '^[0-9a-f]{64}$' AND runtime_epoch>=0 AND source_draft_rev>=0),
          CONSTRAINT ck_report_payload CHECK ((payload_cipher IS NULL)=(payload_hash IS NULL) AND (purged_at IS NULL)=(payload_cipher IS NOT NULL) AND (state NOT IN ('PROPOSED','ACCEPTED') OR payload_cipher IS NOT NULL) AND (payload_cipher IS NULL OR octet_length(payload_cipher) BETWEEN 32 AND 524288)),
          CONSTRAINT ck_report_accept CHECK ((state IN ('ACCEPTED','GRADED','UNGRADABLE') OR (state='CANCELLED' AND accepted_at IS NOT NULL))=(accepted_at IS NOT NULL) AND (accepted_at IS NOT NULL)=(accepted_by IS NOT NULL) AND (accepted_at IS NOT NULL)=(submit_no IS NOT NULL) AND (submit_no IS NULL OR submit_no>=1) AND (accepted_by IS NULL OR accepted_by<>proposer_id) AND (accepted_at IS NULL OR accepted_at>=created_at) AND updated_at>=created_at)
        );
        CREATE UNIQUE INDEX uk_report_live ON public.test_report(test_id) WHERE state IN ('PROPOSED','ACCEPTED');

        ALTER TABLE public.grade_job ADD COLUMN report_id bigint;
        ALTER TABLE public.grade_job ADD COLUMN report_hash char(64);
        ALTER TABLE public.grade_job ALTER COLUMN batch_id DROP NOT NULL;
        ALTER TABLE public.grade_job ALTER COLUMN sample_code DROP NOT NULL;
        ALTER TABLE public.grade_job ALTER COLUMN repeat_no DROP NOT NULL;
        ALTER TABLE public.grade_job ALTER COLUMN input_hash DROP NOT NULL;
        ALTER TABLE public.grade_job DROP CONSTRAINT ck_gj_source;
        ALTER TABLE public.grade_job ADD CONSTRAINT ck_gj_source CHECK (
          (batch_id IS NOT NULL AND sample_code IS NOT NULL AND repeat_no IS NOT NULL AND repeat_no BETWEEN 1 AND 3 AND input_hash IS NOT NULL AND report_id IS NULL AND report_hash IS NULL)
          OR (batch_id IS NULL AND sample_code IS NULL AND repeat_no IS NULL AND input_hash IS NULL AND report_id IS NOT NULL AND report_hash IS NOT NULL)
        );
        ALTER TABLE public.grade_job ADD CONSTRAINT ck_gj_report_hash CHECK (report_hash IS NULL OR report_hash ~ '^[0-9a-f]{64}$');
        ALTER TABLE public.grade_job ADD CONSTRAINT fk_gj_report FOREIGN KEY (snapshot_id,runtime_id,report_id) REFERENCES public.test_report(snapshot_id,runtime_id,id) NOT DEFERRABLE;
        ALTER TABLE public.grade_job ADD CONSTRAINT uk_gj_report UNIQUE (report_id);

        -- 두 부모의 pin은 행 자체와 함께 고정한다. 초안/feedback 원문은 정해진 보관 경로에서만 파기한다.
        CREATE FUNCTION public.guard_test_report() RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
          pinned record;
        BEGIN
          IF TG_OP='DELETE' THEN
            RAISE EXCEPTION 'test_report history cannot be deleted' USING ERRCODE='23514';
          END IF;
          IF TG_OP='INSERT' THEN
            SELECT config_hash,runtime_epoch,draft_rev INTO pinned FROM public.play_test WHERE id=NEW.test_id FOR SHARE;
            IF NOT FOUND OR ROW(NEW.config_hash,NEW.runtime_epoch) IS DISTINCT FROM ROW(pinned.config_hash,pinned.runtime_epoch)
              OR NEW.source_draft_rev>pinned.draft_rev OR NEW.state<>'PROPOSED' OR NEW.accepted_at IS NOT NULL
              OR NEW.payload_cipher IS NULL THEN
              RAISE EXCEPTION 'test_report proposal pins invalid' USING ERRCODE='23514';
            END IF;
            RETURN NEW;
          END IF;
          IF ROW(NEW.id,NEW.report_key,NEW.test_id,NEW.snapshot_id,NEW.runtime_id,NEW.config_hash,NEW.runtime_epoch,NEW.source_draft_rev,NEW.proposer_id,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.report_key,OLD.test_id,OLD.snapshot_id,OLD.runtime_id,OLD.config_hash,OLD.runtime_epoch,OLD.source_draft_rev,OLD.proposer_id,OLD.created_at)
            OR (OLD.purged_at IS NOT NULL AND ROW(NEW.payload_cipher,NEW.payload_hash,NEW.purged_at) IS DISTINCT FROM ROW(OLD.payload_cipher,OLD.payload_hash,OLD.purged_at))
            OR (OLD.accepted_at IS NOT NULL AND ROW(NEW.accepted_by,NEW.accepted_at,NEW.submit_no) IS DISTINCT FROM ROW(OLD.accepted_by,OLD.accepted_at,OLD.submit_no))
            OR (NEW.payload_cipher IS DISTINCT FROM OLD.payload_cipher AND NEW.payload_cipher IS NOT NULL)
            OR (NEW.payload_hash IS DISTINCT FROM OLD.payload_hash AND NEW.payload_hash IS NOT NULL)
            OR (OLD.accepted_at IS NOT NULL AND NEW.state NOT IN ('ACCEPTED','GRADED','UNGRADABLE','CANCELLED'))
            OR (OLD.state IN ('REJECTED','WITHDRAWN','INVALIDATED','GRADED','UNGRADABLE','CANCELLED') AND NEW.state<>OLD.state)
            OR (OLD.state='PROPOSED' AND NEW.state NOT IN ('PROPOSED','REJECTED','WITHDRAWN','INVALIDATED','ACCEPTED','CANCELLED'))
            OR (OLD.state='ACCEPTED' AND NEW.state NOT IN ('ACCEPTED','GRADED','UNGRADABLE','CANCELLED'))
            OR (OLD.accepted_at IS NULL AND NEW.accepted_at IS NOT NULL AND OLD.state<>'PROPOSED') THEN
            RAISE EXCEPTION 'test_report provenance or transition immutable' USING ERRCODE='23514';
          END IF;
          IF OLD.accepted_at IS NULL AND NEW.accepted_at IS NOT NULL THEN
            SELECT draft_rev,next_submit_no INTO pinned FROM public.play_test WHERE id=NEW.test_id FOR SHARE;
            IF NEW.source_draft_rev<>pinned.draft_rev OR NEW.submit_no<>pinned.next_submit_no OR OLD.payload_cipher IS NULL OR NEW.payload_cipher IS DISTINCT FROM OLD.payload_cipher OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash OR NEW.purged_at IS NOT NULL THEN
              RAISE EXCEPTION 'test_report acceptance pin mismatch' USING ERRCODE='23514';
            END IF;
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER trg_test_report BEFORE INSERT OR UPDATE OR DELETE ON public.test_report FOR EACH ROW EXECUTE FUNCTION public.guard_test_report();
        CREATE FUNCTION public.reject_test_report_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'test_report history cannot be truncated' USING ERRCODE='23514';
        END;
        $$;
        CREATE TRIGGER trg_test_report_truncate BEFORE TRUNCATE ON public.test_report FOR EACH STATEMENT EXECUTE FUNCTION public.reject_test_report_truncate();

        CREATE FUNCTION public.guard_test_job_source() RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
          pinned record;
        BEGIN
          IF TG_OP='UPDATE' AND ROW(NEW.snapshot_id,NEW.runtime_id,NEW.batch_id,NEW.sample_code,NEW.repeat_no,NEW.report_id,NEW.report_hash,NEW.input_hash,NEW.config_hash,NEW.rubric_hash,NEW.accepted_at,NEW.deadline_at)
            IS DISTINCT FROM ROW(OLD.snapshot_id,OLD.runtime_id,OLD.batch_id,OLD.sample_code,OLD.repeat_no,OLD.report_id,OLD.report_hash,OLD.input_hash,OLD.config_hash,OLD.rubric_hash,OLD.accepted_at,OLD.deadline_at)
            AND (OLD.report_id IS NOT NULL OR OLD.accepted_at IS NOT NULL) THEN
            RAISE EXCEPTION 'accepted grade_job source immutable' USING ERRCODE='23514';
          END IF;
          IF NEW.report_id IS NOT NULL THEN
            SELECT r.payload_hash,r.config_hash,r.accepted_at,r.state,t.runtime_epoch
              INTO pinned FROM public.test_report r JOIN public.play_test t ON t.id=r.test_id
              WHERE r.id=NEW.report_id FOR SHARE OF r,t;
            IF NOT FOUND OR pinned.accepted_at IS NULL OR pinned.state NOT IN ('ACCEPTED','GRADED','UNGRADABLE','CANCELLED')
              OR (TG_OP='INSERT' AND (pinned.payload_hash IS NULL OR NEW.state<>'QUEUED'))
              OR NEW.config_hash IS DISTINCT FROM pinned.config_hash
              OR NEW.accepted_at IS DISTINCT FROM pinned.accepted_at
              OR NEW.deadline_at IS DISTINCT FROM pinned.accepted_at+interval '120 seconds'
              OR NEW.state='STAGED' THEN
              RAISE EXCEPTION 'grade_job TEST report pin mismatch' USING ERRCODE='23514';
            END IF;
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER trg_test_job_source BEFORE INSERT OR UPDATE ON public.grade_job FOR EACH ROW EXECUTE FUNCTION public.guard_test_job_source();
        CREATE FUNCTION public.guard_test_submit_ordinal() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.next_submit_no<OLD.next_submit_no OR NEW.next_submit_no>OLD.next_submit_no+1
            OR (NEW.next_submit_no=OLD.next_submit_no+1 AND NOT EXISTS (
              SELECT 1 FROM public.test_report WHERE test_id=NEW.id AND submit_no=OLD.next_submit_no AND accepted_at IS NOT NULL
            )) THEN
            RAISE EXCEPTION 'play_test submission ordinal must advance after acceptance' USING ERRCODE='23514';
          END IF;
          RETURN NEW;
        END;
        $$;
        CREATE TRIGGER trg_test_submit_ordinal BEFORE UPDATE ON public.play_test FOR EACH ROW EXECUTE FUNCTION public.guard_test_submit_ordinal();
        REVOKE ALL ON public.test_report FROM PUBLIC;
        REVOKE ALL ON FUNCTION public.guard_test_report(),public.reject_test_report_truncate(),public.guard_test_job_source(),public.guard_test_submit_ordinal() FROM PUBLIC;
        COMMENT ON TABLE public.test_report IS '공동 보고서 제안·접수 이력; 원문은 행/필드/포맷 AAD로 암호화하며 접수 출처는 불변. 암호·canonical hash 및 권한·원자 접수는 서비스 책임';
        COMMENT ON COLUMN public.play_test.next_submit_no IS '접수마다 증가하는 다음 순번. 정상 판정 횟수와 다름';
        COMMENT ON COLUMN public.grade_job.report_hash IS 'H({formatNo:1,payloadHash:H(P),rubricHash,report}) canonical SHA-256; payload_hash/input_hash와 다름. 암호화 REPORT-1 검증은 신뢰한 서비스 책임';
        COMMENT ON COLUMN public.test_report.payload_hash IS '고정 REPORT-1 canonical UTF-8 SHA-256; 파기 때 암호문과 함께 제거';

        -- 임대/시도 없는 TEST QUEUED 마감은 임대 회수 사건과 구분한다.
        ALTER TABLE public.grade_event DROP CONSTRAINT ck_grade_event_shape;
        ALTER TABLE public.grade_event ADD CONSTRAINT ck_grade_event_shape CHECK (
            (actor_kind = 'WORKER' AND event_kind IN ('COMPLETE_APPLIED','COMPLETE_REJECTED') AND attempt_no IS NOT NULL AND request_id IS NOT NULL)
            OR (actor_kind = 'SYSTEM' AND event_kind = 'RECOVERY_EXPIRED' AND attempt_no IS NOT NULL AND request_id IS NULL)
            OR (actor_kind = 'SYSTEM' AND event_kind IN ('JOB_ACTIVATED','JOB_INPUT_REJECTED','JOB_SOURCE_CANCELLED','JOB_LEASE_RECLAIMED','JOB_DEADLINE_EXPIRED') AND attempt_no IS NULL AND request_id IS NULL)
        );
        COMMENT ON CONSTRAINT ck_grade_event_shape ON public.grade_event IS 'V15 기존 주체·사건·시도 조합 보존; 시도 없는 TEST 마감은 SYSTEM JOB_DEADLINE_EXPIRED로 구분';
        """;
    }

    /** V23 TEST 작업 출처 보호 범위 수정 원본의 마지막 LF까지 그대로 반환한다. */
    private static String sql23() {
        return """
        -- TEST 출처의 불변성은 유지하고 보고서 없는 BATCH 갱신 계약은 보존한다.
        CREATE OR REPLACE FUNCTION public.guard_test_job_source() RETURNS trigger LANGUAGE plpgsql AS $$
        DECLARE
          pinned record;
        BEGIN
          IF TG_OP='UPDATE' AND ROW(NEW.snapshot_id,NEW.runtime_id,NEW.batch_id,NEW.sample_code,NEW.repeat_no,NEW.report_id,NEW.report_hash,NEW.input_hash,NEW.config_hash,NEW.rubric_hash,NEW.accepted_at,NEW.deadline_at)
            IS DISTINCT FROM ROW(OLD.snapshot_id,OLD.runtime_id,OLD.batch_id,OLD.sample_code,OLD.repeat_no,OLD.report_id,OLD.report_hash,OLD.input_hash,OLD.config_hash,OLD.rubric_hash,OLD.accepted_at,OLD.deadline_at)
            AND (OLD.report_id IS NOT NULL OR NEW.report_id IS NOT NULL) THEN
            RAISE EXCEPTION 'accepted grade_job source immutable' USING ERRCODE='23514';
          END IF;
          IF NEW.report_id IS NOT NULL THEN
            SELECT r.payload_hash,r.config_hash,r.accepted_at,r.state,t.runtime_epoch
              INTO pinned FROM public.test_report r JOIN public.play_test t ON t.id=r.test_id
              WHERE r.id=NEW.report_id FOR SHARE OF r,t;
            IF NOT FOUND OR pinned.accepted_at IS NULL OR pinned.state NOT IN ('ACCEPTED','GRADED','UNGRADABLE','CANCELLED')
              OR (TG_OP='INSERT' AND (pinned.payload_hash IS NULL OR NEW.state<>'QUEUED'))
              OR NEW.config_hash IS DISTINCT FROM pinned.config_hash
              OR NEW.accepted_at IS DISTINCT FROM pinned.accepted_at
              OR NEW.deadline_at IS DISTINCT FROM pinned.accepted_at+interval '120 seconds'
              OR NEW.state='STAGED' THEN
              RAISE EXCEPTION 'grade_job TEST report pin mismatch' USING ERRCODE='23514';
            END IF;
          END IF;
          RETURN NEW;
        END;
        $$;
        """;
    }
}
