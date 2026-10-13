package com.reasoning.common.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.game.service.PublicGameService;
import com.reasoning.common.story.service.PlaytestReportFixture;
import com.reasoning.common.story.service.StoryReleaseEvidenceService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

/** 합성 검수 원고가 진짜 공개 근거 없이 PUBLIC 방으로 승격되지 않는 최초 차단 지점. */
@SpringBootTest
@Testcontainers
@Import({com.reasoning.common.member.LocalMemberAuthIT.Installation.class,
        PlaytestReportFixture.TestAssembly.class})
class PublicSoloFlowIT extends PlaytestReportFixture {
    @Autowired PublicGameService games;
    @Autowired StoryReleaseEvidenceService evidence;

    @Test
    void syntheticReviewSnapshotCannotEnterPublicWithoutDualModeEvidenceAndFriendlyCollection() throws Exception {
        playtestPolicy();
        var first = account("public-source-a-" + UUID.randomUUID() + "@example.invalid");
        var second = account("public-source-b-" + UUID.randomUUID() + "@example.invalid");
        String firstToken = first.path("accessToken").asText();
        UUID firstKey = invitations.getMemberIdentity(firstToken, UUID.randomUUID()).memberKey();
        UUID secondKey = invitations.getMemberIdentity(second.path("accessToken").asText(), UUID.randomUUID()).memberKey();
        assertThat(firstKey).isNotEqualTo(secondKey);
        UUID invitation = invitation(List.of(firstKey, secondKey));
        var source = db.queryForMap("SELECT v.id AS version_id,t.snapshot_id,t.runtime_id,t.runtime_epoch,"
                + "t.config_hash FROM play_test t JOIN story_version v ON v.id=t.version_id WHERE t.test_key=?", invitation);
        var assessment = evidence.evaluateRelease(((Number) source.get("version_id")).longValue(),
                ((Number) source.get("snapshot_id")).longValue(),
                ((Number) source.get("runtime_id")).longValue(),
                ((Number) source.get("runtime_epoch")).longValue(),
                (String) source.get("config_hash"), Long.MAX_VALUE);
        assertThat(assessment.state()).isEqualTo(StoryReleaseEvidenceService.State.MISSING);
        assertThat(assessment.reason()).startsWith("BLIND_TEST_");
        assertThatThrownBy(() -> games.stories(firstToken, null, 10, UUID.randomUUID()))
                .hasMessageContaining("FRIENDLY_COLLECTION_NOT_READY");
        assertThat(db.queryForObject("SELECT count(*) FROM story_release WHERE snapshot_id=?", Integer.class,
                source.get("snapshot_id"))).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM game_room WHERE purpose='PUBLIC' AND snapshot_id=?",
                Integer.class, source.get("snapshot_id"))).isZero();
    }
}
