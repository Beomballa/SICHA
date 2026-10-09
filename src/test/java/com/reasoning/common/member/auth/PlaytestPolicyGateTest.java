package com.reasoning.common.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PlaytestPolicyGateTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final MemberAuthConfiguration.Properties properties =
            new MemberAuthConfiguration.Properties();
    private final PlaytestPolicyGate gate = new PlaytestPolicyGate(db, properties);

    @Test
    void disabledByDefaultDoesNotReadPolicyOrEvidence() {
        assertThatThrownBy(gate::prepare).hasMessageContaining("PLAYTEST_COLLECTION_NOT_READY");
        verifyNoInteractions(db);
    }

    @Test
    void memberAuthSwitchAndEvidencePathCannotEnablePlaytest() {
        properties.setCollectionEnabled(true);
        properties.setEnvCode("test");
        properties.setEvidenceRegistryFile("/protected/member-evidence.json");
        assertThatThrownBy(gate::prepare).hasMessageContaining("PLAYTEST_COLLECTION_NOT_READY");
        verifyNoInteractions(db);
    }

    @Test
    void separateSwitchWithoutProtectedManifestFailsClosed() {
        properties.setEnvCode("test");
        properties.setPlaytestCollectionEnabled(true);
        assertThatThrownBy(gate::prepare).hasMessageContaining("PLAYTEST_COLLECTION_NOT_READY");
        verifyNoInteractions(db);
    }

    @Test
    void noticeCommitmentIsNotMemberAuthCommitment() {
        var notice = new ObjectMapper().createObjectNode();
        notice.put("version", "v1");
        notice.put("body", "Consent");
        notice.put("contact", "operator");
        assertThat(PlaytestPolicyGate.noticeHash("PT1", notice))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(MemberPolicyGate.noticeHash("PT1", notice));
    }
}
