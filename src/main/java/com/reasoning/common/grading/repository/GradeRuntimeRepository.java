package com.reasoning.common.grading.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** 배포 전용 불변 실행 구성 등록·조회. HTTP 등록이나 가용성 전환은 제공하지 않는다. */
@Repository
public class GradeRuntimeRepository {
    private static final int CONFIG_BYTES = 131072;
    private static final ObjectMapper MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String COLUMNS =
            "id, code, config_hash, config_data::text AS config_json, state, epoch, created_at,"
                + " updated_at";
    private static final RowMapper<RuntimeRow> ROW =
            (row, number) ->
                    new RuntimeRow(
                            row.getLong("id"),
                            row.getString("code"),
                            row.getString("config_hash"),
                            row.getString("config_json"),
                            row.getString("state"),
                            row.getLong("epoch"),
                            row.getObject("created_at", OffsetDateTime.class),
                            row.getObject("updated_at", OffsetDateTime.class));
    private final JdbcTemplate jdbc;

    /**
     * @param jdbc null이 아닌 배포 경로의 DB 연결 도구
     */
    public GradeRuntimeRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * 신뢰된 배포 구성만 등록한다. 같은 코드·해시·JSONB 값은 기존 행을 반환하며 어떤 기존 값도 갱신하지 않는다. INSERT의 유니크 충돌 대기로 동시 등록을
     * 직렬화하고 별도 SELECT의 새 사본에서 승자를 읽는다. 자동 커밋 또는 READ COMMITTED 호출 트랜잭션을 사용해야 한다. 더 강한 격리의 재시도는 호출자
     * 책임이다. 해시는 원문 바이트의 해시가 아니라 프롬프트·스키마 등 의미 구성을 결속한 호출자 manifest이다. 형식 검사만으로 올바른 manifest나 비밀값
     * 부재를 증명하지 않는다. AVAILABLE은 GRADE 승인이 아니다.
     *
     * @param code 대문자 영숫자·밑줄 1~80자, null 불가
     * @param configHash 소문자 SHA-256 64자리, null 불가
     * @param configJson 중복 키·후행 토큰 없는 JSON 객체, UTF-8 원문 및 JSONB 저장 표현 131072바이트 이하
     * @return 등록된 불변 구성 행
     * @throws IllegalArgumentException 입력 형식·크기가 잘못된 경우, 원문을 예외에 포함하지 않음
     * @throws IllegalStateException 같은 코드의 불변 구성이 다른 경우
     * @throws DataAccessException 저장소 실패인 경우, DB 오류의 구성 원문을 공개하지 않음
     */
    public RuntimeRow registerRuntime(String code, String configHash, String configJson) {
        if (code == null
                || !code.matches("[A-Z0-9_]{1,80}")
                || configHash == null
                || !configHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INVALID_RUNTIME_REGISTRATION");
        }
        validateJson(configJson);
        try {
            Integer storedBytes =
                    jdbc.queryForObject(
                            "SELECT octet_length(?::jsonb::text)", Integer.class, configJson);
            if (storedBytes == null || storedBytes > CONFIG_BYTES) {
                throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION");
            }
            jdbc.update(
                    """
                    INSERT INTO public.grade_runtime(code,config_hash,config_data,state)
                    VALUES (?, ?, ?::jsonb, 'AVAILABLE')
                    ON CONFLICT ON CONSTRAINT uk_grade_runtime_code DO NOTHING
                    """,
                    code,
                    configHash,
                    configJson);
            var rows =
                    jdbc.query(
                            "SELECT "
                                    + COLUMNS
                                    + ", (config_hash = ? AND config_data = ?::jsonb) AS matches"
                                    + " FROM public.grade_runtime WHERE code = ?",
                            (row, number) -> {
                                if (!row.getBoolean("matches")) {
                                    throw new IllegalStateException(
                                            "RUNTIME_CONFIGURATION_CONFLICT");
                                }
                                return ROW.mapRow(row, number);
                            },
                            configHash,
                            configJson,
                            code);
            if (rows.size() != 1) {
                throw new IllegalStateException("RUNTIME_REGISTRATION_NOT_VISIBLE");
            }
            return rows.getFirst();
        } catch (DataAccessException failure) {
            // PostgreSQL 오류 상세나 JDBC 인자에 구성 원문이 포함될 수 있으므로 원인도 전파하지 않는다.
            throw new DataAccessResourceFailureException("RUNTIME_STORAGE_FAILURE");
        }
    }

    /**
     * @param id 양수 내부 식별자
     * @return 없는 식별자는 빈 Optional, 존재하면 불변 행 사본
     * @throws IllegalArgumentException 식별자가 양수가 아닌 경우
     */
    public Optional<RuntimeRow> getRuntimeDetail(long id) {
        return find(id, false);
    }

    /**
     * 호출자의 트랜잭션 종료까지 행을 잠근다. 미래 실행 서비스가 정해진 루트 잠금 순서로 호출한다. 잠금 대기 상한·잠금 후 실제 DB 시각 검사는 호출자 책임이다.
     *
     * @param id 양수 내부 식별자
     * @return 없는 식별자는 빈 Optional
     * @throws IllegalArgumentException 식별자가 양수가 아닌 경우
     * @throws IllegalStateException 실제 쓰기 트랜잭션 없이 호출한 경우
     */
    public Optional<RuntimeRow> lockRuntime(long id) {
        positiveId(id);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("RUNTIME_LOCK_REQUIRES_TRANSACTION");
        }
        return find(id, true);
    }

    /** 식별자로 한 행만 읽으며 구성 내용이 포함된 DB 오류를 차단한다. */
    private Optional<RuntimeRow> find(long id, boolean lock) {
        positiveId(id);
        try {
            return jdbc
                    .query(
                            "SELECT "
                                    + COLUMNS
                                    + " FROM public.grade_runtime WHERE id = ?"
                                    + (lock ? " FOR UPDATE" : ""),
                            ROW,
                            id)
                    .stream()
                    .findFirst();
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("RUNTIME_STORAGE_FAILURE");
        }
    }

    /** JSON의 문법·객체·UTF-8 예산을 검사하되 실행 구성의 새 필드 스키마를 만들지 않는다. */
    private static void validateJson(String json) {
        if (json == null
                || json.length() > CONFIG_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > CONFIG_BYTES) {
            throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION");
        }
        // 잘못된 UTF-16이 UTF-8 대체 문자로 조용히 변환되지 않게 검사한다.
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= json.length() || !Character.isLowSurrogate(json.charAt(i))) {
                    throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION");
            }
        }
        try {
            var tree = MAPPER.readTree(json);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION");
            }
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION");
        }
    }

    /** 내부 식별자는 양수만 허용한다. */
    private static void positiveId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("INVALID_RUNTIME_ID");
        }
    }

    /** JSON은 공유 가변 노드가 아닌 불변 문자열 사본이며 기본 문자열 표현에 구성 원문을 노출하지 않는다. */
    public record RuntimeRow(
            long id,
            String code,
            String configHash,
            String configJson,
            String state,
            long epoch,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        @Override
        public String toString() {
            return "RuntimeRow[id=" + id + ", state=" + state + ", epoch=" + epoch + "]";
        }
    }
}
