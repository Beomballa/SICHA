package com.reasoning.common.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
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

/** 실제 컨텍스트 활성화·종료와 세 단계의 실패 후 재시도를 확인하며 예약 삭제는 실행하지 않는다. */
class MemberAuthCleanupConfigurationTest {
    private final MemberAuthCleanupService cleanup = mock(MemberAuthCleanupService.class);
    private final MemberAuthAuditCleanupService audit = mock(MemberAuthAuditCleanupService.class);
    private final MemberAuthConfiguration.PresenceCleanup presence =
            mock(MemberAuthConfiguration.PresenceCleanup.class);
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MemberAuthConfiguration.CleanupConfiguration.class)
                    .withBean(MemberAuthConfiguration.PresenceCleanup.class, () -> presence)
                    .withBean(MemberAuthCleanupService.class, () -> cleanup)
                    .withBean(MemberAuthAuditCleanupService.class, () -> audit);

    @Test
    void absentAndExplicitFalseDoNotCreateOrInvokeSchedulerEvenWithOtherFlagsTrue() {
        runner.withPropertyValues(
                        "app.member-auth.collection-enabled=true", "app.member-web.enabled=true")
                .run(
                        context -> {
                            assertThat(context)
                                    .doesNotHaveBean(
                                            MemberAuthConfiguration.CleanupLifecycle.class);
                            verifyNoInteractions(presence, cleanup, audit);
                        });
        runner.withPropertyValues(
                        "app.member-auth.cleanup-enabled=false",
                        "app.member-auth.collection-enabled=true",
                        "app.member-web.enabled=true")
                .run(
                        context -> {
                            assertThat(context)
                                    .doesNotHaveBean(
                                            MemberAuthConfiguration.CleanupLifecycle.class);
                            verifyNoInteractions(presence, cleanup, audit);
                        });
    }

    @Test
    void explicitEnableStartsIndependentlyAndContextCloseStopsAllThreeCalls() {
        MemberAuthConfiguration.CleanupLifecycle[] owned =
                new MemberAuthConfiguration.CleanupLifecycle[1];
        runner.withPropertyValues(
                        "app.member-auth.cleanup-enabled=true",
                        "app.member-auth.collection-enabled=false",
                        "app.member-web.enabled=false")
                .run(
                        context -> {
                            owned[0] =
                                    context.getBean(MemberAuthConfiguration.CleanupLifecycle.class);
                            assertThat(owned[0].isRunning()).isTrue();
                            verifyNoInteractions(presence, cleanup, audit);
                            owned[0].tick();
                            var order = inOrder(presence, cleanup, audit);
                            order.verify(presence).cleanup(100);
                            order.verify(cleanup).cleanup(100);
                            order.verify(audit).cleanup(100);
                        });
        assertThat(owned[0].isRunning()).isFalse();
        owned[0].tick();
        verify(presence, times(1)).cleanup(100);
        verify(cleanup, times(1)).cleanup(100);
        verify(audit, times(1)).cleanup(100);
    }

    @Test
    void firstFailureSkipsBothLaterPhasesAndRetries() {
        doThrow(new IllegalStateException("synthetic-presence-secret-sql"))
                .doReturn(1)
                .when(presence)
                .cleanup(100);
        failureTicks(0);
    }

    @Test
    void authFailurePreservesPresenceCommitAndSkipsAuditThenRetries() {
        doThrow(new IllegalStateException("synthetic-auth-secret-sql"))
                .doReturn(new MemberAuthCleanupService.Counts(0, 0, 0, 0, 0))
                .when(cleanup)
                .cleanup(100);
        failureTicks(1);
    }

    @Test
    void auditFailureDoesNotClaimEarlierPresenceArtifactOrLimiterRollback() {
        var presenceCommits = new java.util.concurrent.atomic.AtomicInteger();
        var artifactCommits = new java.util.concurrent.atomic.AtomicInteger();
        var limiterCommits = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> presenceCommits.incrementAndGet())
                .when(presence)
                .cleanup(100);
        org.mockito.Mockito.doAnswer(
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

    /**
     * 단계 실패의 순서·비밀 제거·같은 실행기의 재시도와 종료를 검증한다.
     *
     * @param failedPhase 실패한 접속 근거·인증·감사 단계의 번호 0~2
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
                                if (failedPhase == 0) verifyNoInteractions(cleanup, audit);
                                if (failedPhase == 1) verifyNoInteractions(audit);
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
                                assertThat(lifecycle.isRunning()).isTrue();
                                var order = inOrder(presence, cleanup, audit);
                                if (failedPhase == 0) {
                                    order.verify(presence, times(2)).cleanup(100);
                                } else {
                                    order.verify(presence).cleanup(100);
                                    order.verify(cleanup).cleanup(100);
                                    if (failedPhase == 2) order.verify(audit).cleanup(100);
                                    order.verify(presence).cleanup(100);
                                }
                                order.verify(cleanup).cleanup(100);
                                order.verify(audit).cleanup(100);
                                order.verifyNoMoreInteractions();
                                assertThat(events.list).hasSize(1);
                                assertThat(events.list.getFirst().getFormattedMessage())
                                        .isEqualTo("MEMBER_AUTH_CLEANUP_FAILED");
                                assertThat(events.list.getFirst().getThrowableProxy()).isNull();
                                lifecycle.stop();
                                lifecycle.stop();
                                lifecycle.tick();
                                verify(presence, times(2)).cleanup(100);
                                verify(cleanup, times(failedPhase == 0 ? 1 : 2)).cleanup(100);
                                verify(audit, times(failedPhase == 2 ? 2 : 1)).cleanup(100);
                            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void enabledContextRequiresPresenceProviderRatherThanSilentFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemberAuthConfiguration.CleanupConfiguration.class)
                .withBean(MemberAuthCleanupService.class, () -> cleanup)
                .withBean(MemberAuthAuditCleanupService.class, () -> audit)
                .withPropertyValues("app.member-auth.cleanup-enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            verifyNoInteractions(cleanup, audit);
                        });
    }

    @Test
    void enabledContextRequiresAuditProviderRatherThanTwoPhaseFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemberAuthConfiguration.CleanupConfiguration.class)
                .withBean(MemberAuthConfiguration.PresenceCleanup.class, () -> presence)
                .withBean(MemberAuthCleanupService.class, () -> cleanup)
                .withPropertyValues("app.member-auth.cleanup-enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            verifyNoInteractions(presence, cleanup);
                        });
    }
}
