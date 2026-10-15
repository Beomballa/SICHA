package com.reasoning.common.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 실제 컨텍스트의 네 단계 순서·실패·재시도·종료를 검사하며 예약 삭제는 실행하지 않는다. */
class MemberAuthCleanupConfigurationTest {
    private final MemberAuthConfiguration.PresenceCleanup presence =
            mock(MemberAuthConfiguration.PresenceCleanup.class);
    private final MemberAuthCleanupService cleanup = mock(MemberAuthCleanupService.class);
    private final MemberAuthAuditCleanupService audit = mock(MemberAuthAuditCleanupService.class);
    private final MemberAccessCleanupService access = mock(MemberAccessCleanupService.class);
    private final Object[] phases = {presence, cleanup, audit, access};
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MemberAuthConfiguration.CleanupConfiguration.class)
                    .withBean(MemberAuthConfiguration.PresenceCleanup.class, () -> presence)
                    .withBean(MemberAuthCleanupService.class, () -> cleanup)
                    .withBean(MemberAuthAuditCleanupService.class, () -> audit)
                    .withBean(MemberAccessCleanupService.class, () -> access);

    @Test
    void absentAndExplicitFalseDoNotInvokeAnyPhaseEvenWithCollectionAndWebEnabled() {
        for (boolean explicit : new boolean[] {false, true}) {
            var candidate =
                    runner.withPropertyValues(
                            "app.member-auth.collection-enabled=true",
                            "app.member-web.enabled=true");
            if (explicit)
                candidate = candidate.withPropertyValues("app.member-auth.cleanup-enabled=false");
            candidate.run(
                    context -> {
                        assertThat(context)
                                .doesNotHaveBean(MemberAuthConfiguration.CleanupLifecycle.class);
                        verifyNoInteractions(phases);
                    });
        }
    }

    @Test
    void explicitEnableStartsIndependentlyAndContextCloseStopsAllFourCalls() {
        var owned = new MemberAuthConfiguration.CleanupLifecycle[1];
        runner.withPropertyValues(
                        "app.member-auth.cleanup-enabled=true",
                        "app.member-auth.collection-enabled=false",
                        "app.member-web.enabled=false")
                .run(
                        context -> {
                            owned[0] =
                                    context.getBean(MemberAuthConfiguration.CleanupLifecycle.class);
                            assertThat(owned[0].isRunning()).isTrue();
                            verifyNoInteractions(phases);
                            owned[0].tick();
                            var order = inOrder(phases);
                            order.verify(presence).cleanup(100);
                            order.verify(cleanup).cleanup(100);
                            order.verify(audit).cleanup(100);
                            order.verify(access).cleanup(100);
                            order.verifyNoMoreInteractions();
                        });
        assertThat(owned[0].isRunning()).isFalse();
        owned[0].tick();
        verify(presence, times(1)).cleanup(100);
        verify(cleanup, times(1)).cleanup(100);
        verify(audit, times(1)).cleanup(100);
        verify(access, times(1)).cleanup(100);
    }

    @Test
    void presenceFailureSkipsLaterPhasesAndRetries() {
        doThrow(new IllegalStateException("synthetic-presence-secret-sql"))
                .doReturn(1)
                .when(presence)
                .cleanup(100);
        failureTicks(0);
    }

    @Test
    void authFailureSkipsAuditAndAccessThenRetries() {
        doThrow(new IllegalStateException("synthetic-auth-secret-sql"))
                .doReturn(new MemberAuthCleanupService.Counts(0, 0, 0, 0, 0))
                .when(cleanup)
                .cleanup(100);
        failureTicks(1);
    }

    @Test
    void auditFailurePreservesEarlierIndependentCommits() {
        var presenceCommits = new java.util.concurrent.atomic.AtomicInteger();
        var artifactCommits = new java.util.concurrent.atomic.AtomicInteger();
        var limiterCommits = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> presenceCommits.incrementAndGet()).when(presence).cleanup(100);
        doAnswer(
                        invocation -> {
                            artifactCommits.incrementAndGet();
                            limiterCommits.incrementAndGet();
                            return new MemberAuthCleanupService.Counts(1, 1, 1, 1, 1);
                        })
                .when(cleanup)
                .cleanup(100);
        doThrow(new IllegalStateException("synthetic-audit-secret-sql"))
                .doReturn(new MemberAuthAuditCleanupService.Counts(0, 0, null))
                .when(audit)
                .cleanup(100);
        failureTicks(2);
        assertThat(presenceCommits.get()).isEqualTo(2);
        assertThat(artifactCommits.get()).isEqualTo(2);
        assertThat(limiterCommits.get()).isEqualTo(2);
    }

    @Test
    void accessFailurePreservesAuditCommitAndRetries() {
        var auditCommits = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        invocation -> {
                            auditCommits.incrementAndGet();
                            return new MemberAuthAuditCleanupService.Counts(
                                    1, 0, java.util.UUID.randomUUID());
                        })
                .when(audit)
                .cleanup(100);
        doThrow(new IllegalStateException("synthetic-access-secret-sql"))
                .doReturn(new MemberAccessCleanupService.Counts(0, 0, null))
                .when(access)
                .cleanup(100);
        failureTicks(3);
        assertThat(auditCommits.get()).isEqualTo(2);
    }

    /**
     * 단계 실패 뒤 뒤쪽 호출 중단·같은 실행기 재시도·비밀 없는 경고·종료를 검증한다.
     *
     * @param failedPhase 접속 근거·인증·감사·접근 순서의 실패 위치 0~3
     */
    private void failureTicks(int failedPhase) {
        Logger logger =
                (Logger) LoggerFactory.getLogger(MemberAuthConfiguration.CleanupLifecycle.class);
        var events = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            runner.withPropertyValues("app.member-auth.cleanup-enabled=true")
                    .run(
                            context -> {
                                var lifecycle =
                                        context.getBean(
                                                MemberAuthConfiguration.CleanupLifecycle.class);
                                Object executor =
                                        org.springframework.test.util.ReflectionTestUtils.getField(
                                                lifecycle, "executor");
                                lifecycle.tick();
                                for (int i = failedPhase + 1; i < phases.length; i++)
                                    verifyNoInteractions(phases[i]);
                                assertThat(lifecycle.isRunning()).isTrue();
                                lifecycle.tick();
                                assertThat(
                                                context.getBean(
                                                        MemberAuthConfiguration.CleanupLifecycle
                                                                .class))
                                        .isSameAs(lifecycle);
                                assertThat(
                                                org.springframework.test.util.ReflectionTestUtils
                                                        .getField(lifecycle, "executor"))
                                        .isSameAs(executor);
                                var order = inOrder(phases);
                                if (failedPhase == 0) {
                                    order.verify(presence, times(2)).cleanup(100);
                                } else {
                                    order.verify(presence).cleanup(100);
                                    order.verify(cleanup).cleanup(100);
                                    if (failedPhase >= 2) order.verify(audit).cleanup(100);
                                    if (failedPhase == 3) order.verify(access).cleanup(100);
                                    order.verify(presence).cleanup(100);
                                }
                                order.verify(cleanup).cleanup(100);
                                order.verify(audit).cleanup(100);
                                order.verify(access).cleanup(100);
                                order.verifyNoMoreInteractions();
                                assertThat(events.list).hasSize(1);
                                assertThat(events.list.getFirst().getFormattedMessage())
                                        .isEqualTo("MEMBER_AUTH_CLEANUP_FAILED");
                                assertThat(events.list.getFirst().getThrowableProxy()).isNull();
                                lifecycle.stop();
                                lifecycle.stop();
                                lifecycle.tick();
                                verify(presence, times(2)).cleanup(100);
                                verify(cleanup, times(failedPhase >= 1 ? 2 : 1)).cleanup(100);
                                verify(audit, times(failedPhase >= 2 ? 2 : 1)).cleanup(100);
                                verify(access, times(failedPhase == 3 ? 2 : 1)).cleanup(100);
                            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    /** 각 단계가 빠졌을 때 활성화 실패를 확인하여 조용한 부분 실행을 금지한다. */
    @Test
    void enabledContextRequiresEveryPhaseProvider() {
        for (int missing = 0; missing < phases.length; missing++) {
            var candidate =
                    new ApplicationContextRunner()
                            .withUserConfiguration(
                                    MemberAuthConfiguration.CleanupConfiguration.class)
                            .withPropertyValues("app.member-auth.cleanup-enabled=true");
            if (missing != 0)
                candidate =
                        candidate.withBean(
                                MemberAuthConfiguration.PresenceCleanup.class, () -> presence);
            if (missing != 1)
                candidate = candidate.withBean(MemberAuthCleanupService.class, () -> cleanup);
            if (missing != 2)
                candidate = candidate.withBean(MemberAuthAuditCleanupService.class, () -> audit);
            if (missing != 3)
                candidate = candidate.withBean(MemberAccessCleanupService.class, () -> access);
            candidate.run(
                    context -> {
                        assertThat(context).hasFailed();
                        verifyNoInteractions(phases);
                    });
        }
    }
}
