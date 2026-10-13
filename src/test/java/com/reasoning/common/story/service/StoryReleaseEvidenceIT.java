package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.List;
import java.util.Map;

/** 합성 조회는 실제 사람 BLIND·품질의 증거가 아니라 누락 시 닫힘만 검증한다. */
class StoryReleaseEvidenceIT {
    private static final String PIN="a".repeat(64);
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final StoryReleaseEvidenceService evidence=new StoryReleaseEvidenceService(db,mock(StoryService.class),new ObjectMapper());

    @Test
    void wrongSnapshotIsUnavailableBeforeReadingAnyCandidates() {
        when(db.queryForObject(anyString(),eq(Boolean.class),any(),any())).thenReturn(false);
        assertThat(evidence.evaluateRelease(1,2,3,4,PIN,5).state())
                .isEqualTo(StoryReleaseEvidenceService.State.UNAVAILABLE);
    }

    @Test
    void currentRuntimePinIsRequired() {
        when(db.queryForObject(anyString(),eq(Boolean.class),any(),any())).thenReturn(true);
        when(db.queryForObject(anyString(),eq(Boolean.class),any(),any(),any())).thenReturn(false);
        assertThat(evidence.evaluateRelease(1,2,3,4,PIN,5).reason()).isEqualTo("RUNTIME_CHANGED");
    }

    @Test
    void missingPairCannotBeTurnedIntoPassByReceipt() {
        when(db.queryForObject(anyString(),eq(Boolean.class),any(),any())).thenReturn(true);
        when(db.queryForObject(anyString(),eq(Boolean.class),any(),any(),any())).thenReturn(true);
        when(db.queryForList(anyString(),eq(1L))).thenReturn(List.of(Map.of("role_a","A","role_b","B")));
        assertThat(evidence.evaluateRelease(1,2,3,4,PIN,5).reason()).isEqualTo("BLIND_TEST_A_B");
    }

    @Test
    void collectorsRequireRealModesFeedbackExposureAndWorkerProvenance() {
        // 원본 조회 SQL 자체도 검수한다. 생산 권위가 아닌 mock DB 결과는 합격으로 계산하지 않는다.
        when(db.queryForObject(anyString(),eq(Boolean.class),any(),any())).thenReturn(true);
        when(db.queryForObject(anyString(),eq(Boolean.class),any(),any(),any())).thenReturn(true);
        when(db.queryForList(anyString(),eq(1L))).thenReturn(List.of(Map.of("role_a","A","role_b","B")));
        evidence.evaluateRelease(1,2,3,4,PIN,5);
        var queries=org.mockito.Mockito.mockingDetails(db).getInvocations().stream()
                .filter(i->i.getMethod().getName().equals("query") && i.getArguments()[1] instanceof RowCallbackHandler)
                .map(i->(String)i.getArguments()[0]).toList();
        assertThat(queries).hasSize(2);
        assertThat(queries.get(0)).contains("t.mode='BLIND'","audit.action='TEST_MATERIALS'","ma.feedback_cipher IS NOT NULL",
                "mb.feedback_cipher IS NOT NULL","attempt.state='SUCCEEDED'","retention.raw_until>now()");
        assertThat(queries.get(1)).contains("g.purpose='REVIEW_SOLO_QUALIFICATION'","g.qualification_kind='BLIND'",
                "release_solo_feedback","attempt.state='SUCCEEDED'","g.selected_member_id");
    }
}
