package com.reasoning.common.grading.repository;

import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier.VerifiedRuntime;
import com.reasoning.common.grading.repository.GradeSourceRepository.LockedSource;
import com.reasoning.common.grading.service.FrozenDatasetValidator;
import com.reasoning.common.grading.service.FrozenDatasetValidator.ValidatedDataset;
import com.reasoning.common.story.model.FrozenSnapshotCodec;
import com.reasoning.common.story.model.FrozenSnapshotCodec.FrozenSnapshot;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/** 실제 전체 사본의 무결성만 검사한다. 출처 권한·임대·현재 실행 자격을 부여하지 않는다. */
public final class GradeFrozenInputRepository {
    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 호출자와 동일 DataSource의 JDBC 도구, null 불가
     */
    public GradeFrozenInputRepository(JdbcTemplate jdbc) {
        if (jdbc == null || jdbc.getDataSource() == null)
            throw new IllegalArgumentException("INVALID_FROZEN_INPUT");
        this.jdbc = jdbc;
    }

    /**
     * 저장된 실제 JSONB 전체 사본을 decode하고 선언 해시·물리 부모 식별자와 비교한다. 공개 사실 증거 또는 외부 저장소 증거를 전달해도 업무 권한은 생기지
     * 않는다.
     *
     * @param root 실제 루트 사실 증거, null 불가; 실행 권한은 소유 source 저장소가 별도 검사
     * @param runtime 실제 설치 검증값, null 불가
     * @return 전체 검증 집합
     * @throws IllegalStateException 실제 쓰기 READ COMMITTED 연결이 없거나 선언과 사본이 다르면 고정 오류
     * @throws IllegalArgumentException codec 또는 집합 계약이 잘못된 경우, 원인 없는 고정 오류
     * @throws DataAccessResourceFailureException 원인 없는 FROZEN_INPUT_STORAGE_FAILURE
     */
    public ValidatedDataset dataset(LockedSource root, VerifiedRuntime runtime) {
        if (root == null || runtime == null)
            throw new IllegalArgumentException("INVALID_FROZEN_INPUT");
        try {
            requireTransaction();
            var saved = loadSavedFacts(root.snapshotId());
            var frozen = saved.frozen();
            var validated = saved.dataset();
            var payload = frozen.payload();
            if (saved.storyId() != root.storyId()
                    || saved.versionId() != root.versionId()
                    || !frozen.payloadHash().equals(root.payloadHash())
                    || !frozen.rubricHash().equals(root.rubricHash())
                    || !frozen.datasetHash().equals(root.datasetHash())
                    || !frozen.inputHash(root.sampleCode()).equals(root.inputHash())
                    || payload.get("formatNo").longValue() != root.snapshotFormat()
                    || !payload.get("sourceRev")
                            .textValue()
                            .equals(Long.toString(root.snapshotRev()))
                    || !payload.get("policy")
                            .get("policyCode")
                            .textValue()
                            .equals(root.policyCode())
                    || !runtime.profile().policyCode().equals(root.policyCode())) throw rejected();
            return validated;
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("FROZEN_INPUT_STORAGE_FAILURE");
        }
    }

    /**
     * 실제 물리 부모와 저장된 전체 사본만 검증한다. 현재 정책이나 설치·실행 권한을 부여하지 않는다.
     *
     * @param snapshotId 실제 양의 사본 식별자
     * @return 원문 문자열화를 차단한 저장 사실
     * @throws IllegalStateException 연결 경계 또는 물리 부모·사본 불일치
     * @throws DataAccessResourceFailureException 고정 저장 실패
     */
    public SavedDataset loadSavedFacts(long snapshotId) {
        if (snapshotId <= 0) throw new IllegalArgumentException("INVALID_FROZEN_INPUT");
        try {
            requireTransaction();
            var row =
                    jdbc.queryForMap(
                            """
                            SELECT f.id,f.version_id,f.edit_rev,f.format_no,f.payload::text AS payload,
                                octet_length(f.payload::text) AS payload_bytes,v.story_id,v.version_no,s.code
                            FROM public.review_snapshot f JOIN public.story_version v ON v.id=f.version_id
                            JOIN public.story s ON s.id=v.story_id WHERE f.id=?
                            """,
                            snapshotId);
            if (((Number) row.get("payload_bytes")).longValue() > 16 * 1024 * 1024)
                throw rejected();
            var frozen =
                    FrozenSnapshotCodec.decode(
                            row.get("payload").toString().getBytes(StandardCharsets.UTF_8));
            var validated = new FrozenDatasetValidator().validate(frozen);
            var payload = frozen.payload();
            long rev = ((Number) row.get("edit_rev")).longValue();
            long format = ((Number) row.get("format_no")).longValue();
            int version = ((Number) row.get("version_no")).intValue();
            if (!Long.toString(rev).equals(payload.path("sourceRev").textValue())
                    || format != payload.path("formatNo").longValue()
                    || version != payload.path("versionNo").longValue()
                    || !row.get("code").equals(payload.path("storyCode").textValue()))
                throw rejected();
            return new SavedDataset(
                    snapshotId,
                    ((Number) row.get("version_id")).longValue(),
                    ((Number) row.get("story_id")).longValue(),
                    (String) row.get("code"),
                    version,
                    rev,
                    format,
                    payload.path("policy").path("policyCode").textValue(),
                    frozen,
                    validated);
        } catch (DataAccessException failure) {
            throw new DataAccessResourceFailureException("FROZEN_INPUT_STORAGE_FAILURE");
        }
    }

    /**
     * 저장된 정책·전체 사본은 사실이며 현재 실행 자격이 아니다. 문자열화는 원문을 제외한다.
     *
     * @param snapshotId 실제 양의 사본 ID
     * @param versionId 실제 물리 버전 ID
     * @param storyId 실제 물리 사건 ID
     * @param storyCode 실제 부모 사건 코드
     * @param versionNo 실제 부모 버전 번호
     * @param sourceRev 저장된 사본 수정번호
     * @param formatNo 저장된 사본 형식 번호
     * @param policyCode 전체 검증된 저장 정책만 사용
     * @param frozen 엄격히 decode한 전체 사본
     * @param dataset 전체 검증된 fixture 집합
     */
    public record SavedDataset(
            long snapshotId,
            long versionId,
            long storyId,
            String storyCode,
            int versionNo,
            long sourceRev,
            long formatNo,
            String policyCode,
            FrozenSnapshot frozen,
            ValidatedDataset dataset) {
        @Override
        public String toString() {
            return "SavedDataset[snapshotId=" + snapshotId + ", versionId=" + versionId + "]";
        }
    }

    /** JDBC·JPA 관리자가 실제 거래에 결속한 동일 쓰기 연결에서만 읽으며 새 연결을 승인하지 않는다. */
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw boundary();
        Object resource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if (!(resource instanceof ConnectionHolder holder) || holder.getConnectionHandle() == null)
            throw boundary();
        Boolean valid =
                jdbc.execute(
                        (ConnectionCallback<Boolean>)
                                connection -> {
                                    var target = DataSourceUtils.getTargetConnection(connection);
                                    return target
                                                    == DataSourceUtils.getTargetConnection(
                                                            holder.getConnection())
                                            && DataSourceUtils.isConnectionTransactional(
                                                    target, jdbc.getDataSource())
                                            && !target.getAutoCommit()
                                            && !target.isReadOnly()
                                            && target.getTransactionIsolation()
                                                    == Connection.TRANSACTION_READ_COMMITTED;
                                });
        if (!Boolean.TRUE.equals(valid)) throw boundary();
    }

    /** 기존 START 사본 불일치 계약을 유지한다. */
    private static IllegalStateException rejected() {
        return new IllegalStateException("START_NOT_CURRENT");
    }

    /** 원인·연결 문자열을 포함하지 않는다. */
    private static IllegalStateException boundary() {
        return new IllegalStateException("FROZEN_INPUT_REQUIRES_WRITE_READ_COMMITTED");
    }
}
