package com.reasoning.common.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reasoning.common.auth.audit.RequestAuditKernel;
import com.reasoning.common.auth.service.AdminActor;
import com.reasoning.common.grading.repository.GradeRuntimeRepository;
import com.reasoning.web.member.auth.PlaytestMemberController;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 저장 전 형식 거절: 테스트는 PLAYTEST 운영 근거나 회원 동의가 준비되었다고 주장하지 않는다. */
class PlaytestInvitationIT {
    private final StoryService stories = mock(StoryService.class);
    private final GradeRuntimeRepository runtimes = mock(GradeRuntimeRepository.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final PlaytestInvitationService service =
            new PlaytestInvitationService(stories, runtimes, db);

    @Test
    void rejectsDuplicateInviteesBeforeAnyTransaction() {
        UUID member = UUID.randomUUID();
        assertThatThrownBy(() -> create(List.of(member, member), "A", "B"))
                .hasMessageContaining("INVALID_REQUEST");
        verifyNoInteractions(stories, runtimes, db);
    }

    @Test
    void rejectsUnsortedOrMalformedPairBeforeAnyTransaction() {
        List<UUID> members = List.of(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> create(members, "B", "A")).hasMessageContaining("INVALID_REQUEST");
        assertThatThrownBy(() -> create(members, "a", "B")).hasMessageContaining("INVALID_REQUEST");
        verifyNoInteractions(stories, runtimes, db);
    }

    @Test
    void rejectsInvalidIntentionKeyBeforeAnyTransaction() {
        assertThatThrownBy(
                        () ->
                                service.createInvitation(
                                        null,
                                        mock(AdminActor.class),
                                        "ST_X",
                                        1,
                                        0,
                                        1,
                                        "RUNTIME",
                                        "FUNCTIONAL",
                                        "A",
                                        "B",
                                        List.of(UUID.randomUUID(), UUID.randomUUID()),
                                        UUID.nameUUIDFromBytes(new byte[] {1}),
                                        UUID.randomUUID()))
                .hasMessageContaining("INVALID_REQUEST");
        verifyNoInteractions(stories, runtimes, db);
    }

    @Test
    void blindCannotClaimEligibilityWithoutEvidence() {
        assertThatThrownBy(
                        () ->
                                service.createInvitation(
                                        null,
                                        mock(AdminActor.class),
                                        "ST_X",
                                        1,
                                        0,
                                        1,
                                        "RUNTIME",
                                        "BLIND",
                                        "A",
                                        "B",
                                        List.of(UUID.randomUUID(), UUID.randomUUID()),
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("BLIND_INELIGIBLE");
        verifyNoInteractions(stories, runtimes, db);
    }

    @Test
    void revocationRejectsUnverifiedReferenceBeforeDatabaseAccess() {
        assertThatThrownBy(
                        () ->
                                service.revokeInvitation(
                                        null,
                                        mock(AdminActor.class),
                                        "ST_X",
                                        1,
                                        UUID.randomUUID(),
                                        0,
                                        "OPERATIONAL",
                                        "email@example.org",
                                        UUID.randomUUID(),
                                        UUID.randomUUID()))
                .hasMessageContaining("INVALID_REQUEST");
        verifyNoInteractions(stories, runtimes, db);
    }

    @Test
    void sameMemberReceiptReplaysAndAdminKeyConflicts() {
        UUID key = UUID.randomUUID();
        var prior =
                new PlaytestInvitationService.Receipt(
                        null,
                        17L,
                        "INVITATION_ACCEPT",
                        "test:" + key,
                        "intent",
                        key.toString(),
                        "1",
                        "WAITING",
                        Instant.now());
        PlaytestInvitationService.checkMemberReceipt(prior, 17L, "test:" + key, "intent");
        assertThat(prior.rev()).isEqualTo("1");
        assertThatThrownBy(
                        () ->
                                PlaytestInvitationService.checkMemberReceipt(
                                        prior, 17L, "test:" + key, "different-intent"))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
        var admin =
                new PlaytestInvitationService.Receipt(
                        2L,
                        null,
                        "INVITATION_CREATE",
                        "test:23",
                        "intent",
                        "test-key",
                        "0",
                        "WAITING",
                        Instant.now());
        assertThatThrownBy(
                        () ->
                                PlaytestInvitationService.checkMemberReceipt(
                                        admin, 17L, "test:23", "intent"))
                .hasMessageContaining("REQUEST_KEY_CONFLICT");
    }

    @Test
    void withdrawalOrChangedSnapshotInvalidatesConsent() {
        var active =
                new PlaytestInvitationService.TestTarget(
                        23L, true, "REVIEW", 42L, 42L, Instant.now());
        PlaytestInvitationService.requireReview(active);
        assertThatThrownBy(
                        () ->
                                PlaytestInvitationService.requireReview(
                                        new PlaytestInvitationService.TestTarget(
                                                23L, true, "DRAFT", null, 42L, Instant.now())))
                .hasMessageContaining("INVITATION_INVALIDATED");
        assertThatThrownBy(
                        () ->
                                PlaytestInvitationService.requireReview(
                                        new PlaytestInvitationService.TestTarget(
                                                23L, true, "REVIEW", 43L, 42L, Instant.now())))
                .hasMessageContaining("INVITATION_INVALIDATED");
    }

    @Test
    void acceptsCanonicalStringRevisionButRejectsNumericAndLeadingZero() {
        var invitationService = mock(PlaytestInvitationService.class);
        var controller =
                new PlaytestMemberController(
                        invitationService,
                        mock(com.reasoning.common.story.service.PlaytestInvestigationService.class),
                        mock(com.reasoning.common.story.service.PlaytestReportService.class),
                        new ObjectMapper());
        UUID testKey = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        String suffix =
                ",\"inviteGen\":1,\"blindDeclared\":false,\"policyCode\":\"P\","
                        + "\"noticeHash\":\"H\",\"requestKey\":\""
                        + requestKey
                        + "\"}";
        controller.accept(testKey.toString(), request(testKey, "\"0\"" + suffix));
        verify(invitationService)
                .acceptInvitation("x".repeat(43), testKey, 1, 0L, "P", "H", requestKey, testKey);
        assertThatThrownBy(
                        () -> controller.accept(testKey.toString(), request(testKey, "0" + suffix)))
                .hasMessageContaining("INVALID_REQUEST");
        assertThatThrownBy(
                        () ->
                                controller.accept(
                                        testKey.toString(), request(testKey, "\"00\"" + suffix)))
                .hasMessageContaining("INVALID_REQUEST");
        assertThatThrownBy(
                        () ->
                                controller.accept(
                                        testKey.toString(),
                                        request(testKey, "\"9223372036854775808\"" + suffix)))
                .hasMessageContaining("INVALID_REQUEST");
    }

    private MockHttpServletRequest request(UUID requestId, String revisionAndRest) {
        var request = new MockHttpServletRequest("POST", "/api/playtests/test/accept");
        request.setContentType("application/json");
        request.addHeader("Authorization", "Bearer " + "x".repeat(43));
        request.setAttribute(RequestAuditKernel.REQUEST_ID_ATTRIBUTE, requestId);
        request.setContent(
                ("{\"expectedRev\":" + revisionAndRest).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void create(List<UUID> members, String first, String second) {
        service.createInvitation(
                null,
                mock(AdminActor.class),
                "ST_X",
                1,
                0,
                1,
                "RUNTIME",
                "FUNCTIONAL",
                first,
                second,
                members,
                UUID.randomUUID(),
                UUID.randomUUID());
    }
}
