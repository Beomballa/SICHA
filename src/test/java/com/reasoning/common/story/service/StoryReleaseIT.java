package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.member.LocalMemberAuthIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

/** 격리 PostgreSQL에서 실제 세션·검수 부모를 사용한다. 합성 자료는 실제 사람/모델 품질 증명이 아니다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(LocalMemberAuthIT.Installation.class)
public class StoryReleaseIT extends LocalMemberAuthIT {
    @Autowired StoryReleaseService releases;

    private void prepareReview() throws Exception {
        playtestPolicy();
        String a=account("release-a-"+UUID.randomUUID()+"@example.invalid").path("accessToken").asText();
        String b=account("release-b-"+UUID.randomUUID()+"@example.invalid").path("accessToken").asText();
        invitation(List.of(invitations.getMemberIdentity(a,UUID.randomUUID()).memberKey(),
                invitations.getMemberIdentity(b,UUID.randomUUID()).memberKey()));
    }

    /** 근거 출처 없는 등록 runtime은 READY 영수증이나 공개본을 만들 수 없다. */
    @Test
    void missingHumanAndWorkerSourcesCannotProduceReady() throws Exception {
        prepareReview();
        String snapshot=db.queryForObject("SELECT v.current_snapshot_id::text FROM story_version v JOIN story s ON s.id=v.story_id WHERE s.code=? AND v.version_no=1",String.class,invitationStoryCode);
        var check=releases.check(invitationSid,invitationActor,invitationStoryCode,1,"H5_SYNTHETIC");
        assertThat(check.canReady()).isFalse();
        assertThat(check.checks()).anyMatch(item->item.code().equals("PLAYTEST") && !item.state().equals("PASS"));
        assertThatThrownBy(()->releases.ready(invitationSid,invitationActor,invitationStoryCode,1,
                check.editRev(),snapshot,check.evidenceHash(),"H5_SYNTHETIC",UUID.randomUUID()))
                .hasMessageContaining("EVIDENCE_NOT_CONNECTED");
        assertThat(db.queryForObject("SELECT count(*) FROM story_release WHERE snapshot_id=?",Integer.class,Long.parseLong(snapshot))).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM story_action WHERE story_id=(SELECT id FROM story WHERE code=?)",Integer.class,invitationStoryCode)).isZero();
    }

    /** 소유자 자격은 사건 PUBLISH 관계를 대신하지 않으며 비정규 키는 거래 전 거절한다. */
    @Test
    void ownerCannotPublishWithoutDistinctGrant() throws Exception {
        prepareReview();
        assertThatThrownBy(()->releases.publish(invitationSid,invitationActor,invitationStoryCode,1,
                "0","0","1","1",null,false,UUID.randomUUID()))
                .hasMessageContaining("FORBIDDEN");
        assertThatThrownBy(()->releases.ready(invitationSid,invitationActor,invitationStoryCode,1,
                "0","1","a".repeat(64),"H5_SYNTHETIC",UUID.nameUUIDFromBytes(new byte[]{1})))
                .hasMessageContaining("INVALID_REQUEST");
    }

    /** 다른 출처/없는 출처에 대한 사람 PASS는 감사·영수증으로 승격하지 않는다. */
    @Test
    void unconnectedSourceCannotBeReviewed() throws Exception {
        prepareReview();
        assertThatThrownBy(()->releases.review(invitationSid,invitationActor,invitationStoryCode,1,
                "TEST",Long.MAX_VALUE,"PASS",0,"합성 검토 결과","Verifier_123",null,null,UUID.randomUUID()))
                .hasMessageContaining("EVIDENCE_SOURCE_MISSING");
        assertThat(db.queryForObject("SELECT count(*) FROM story_action WHERE story_id=(SELECT id FROM story WHERE code=?)",Integer.class,invitationStoryCode)).isZero();
    }

    /** V11 기존 영수증의 컬럼과 공개 행동을 V34 뒤에도 그대로 사용한다. */
    @Test
    void actionHistoryIsImmutableInPostgres() throws Exception {
        prepareReview();
        String table=db.queryForObject("SELECT to_regclass('public.story_action')::text",String.class);
        assertThat(table).isEqualTo("story_action");
        long owner=db.queryForObject("SELECT owner_id FROM story WHERE code=?",Long.class,invitationStoryCode);
        long story=db.queryForObject("SELECT id FROM story WHERE code=?",Long.class,invitationStoryCode);
        long action=db.queryForObject("INSERT INTO story_action(story_id,request_key,action,actor_id,request_data,result_data) VALUES (?,?,'READY',?,'{}'::jsonb,'{}'::jsonb) RETURNING id",
                Long.class,story,UUID.randomUUID(),owner);
        assertThat(db.queryForObject("SELECT actor_id FROM story_action WHERE id=?",Long.class,action)).isEqualTo(owner);
        assertThatThrownBy(()->db.update("INSERT INTO story_action(story_id,request_key,action,actor_id,request_data,result_data) VALUES (?,?,'INVALID',?,'{}'::jsonb,'{}'::jsonb)",story,UUID.randomUUID(),owner))
                .isInstanceOf(DataAccessException.class);
    }
}
