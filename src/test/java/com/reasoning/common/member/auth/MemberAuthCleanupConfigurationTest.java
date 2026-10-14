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

/** 실제 컨텍스트 활성화·종료와 실패 후 다음 호출을 확인하며 예약 삭제는 실행하지 않는다. */
class MemberAuthCleanupConfigurationTest {
    private final MemberAuthCleanupService cleanup = mock(MemberAuthCleanupService.class);
    private final MemberAuthConfiguration.PresenceCleanup presence =
            mock(MemberAuthConfiguration.PresenceCleanup.class);
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MemberAuthConfiguration.CleanupConfiguration.class)
                    .withBean(MemberAuthConfiguration.PresenceCleanup.class, () -> presence)
                    .withBean(MemberAuthCleanupService.class, () -> cleanup);

    @Test
    void absentAndExplicitFalseDoNotCreateOrInvokeScheduler() {
        runner.run(
                context -> {
                    assertThat(context)
                            .doesNotHaveBean(MemberAuthConfiguration.CleanupLifecycle.class);
                    verifyNoInteractions(presence, cleanup);
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
                            verifyNoInteractions(presence, cleanup);
                        });
    }

    @Test
    void explicitEnableStartsIndependentlyAndContextCloseStopsCalls() {
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
                            verifyNoInteractions(presence, cleanup);
                            owned[0].tick();
                            var order = inOrder(presence, cleanup);
                            order.verify(presence).cleanup(100);
                            order.verify(cleanup).cleanup(100);
                        });
        assertThat(owned[0].isRunning()).isFalse();
        owned[0].tick();
        verify(presence, times(1)).cleanup(100);
        verify(cleanup, times(1)).cleanup(100);
    }

    @Test
    void laterFailureDoesNotPretendEarlierPresenceCommitWasRolledBack() {
        var committed = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> committed.incrementAndGet())
                .when(presence)
                .cleanup(100);
        doThrow(new IllegalStateException("synthetic-secret-sql"))
                .doReturn(new MemberAuthCleanupService.Counts(0, 0, 0, 0, 0))
                .when(cleanup)
                .cleanup(100);
        failureTicks(false);
        assertThat(committed.get()).isEqualTo(2);
    }

    @Test
    void firstFailureSkipsAuthWithoutCancellingRetry() {
        doThrow(new IllegalStateException("synthetic-presence-secret-sql"))
                .doReturn(1)
                .when(presence)
                .cleanup(100);
        failureTicks(true);
    }

    /** 첫/후속 단계 실패의 순서·비밀 제거·독립 재시도와 종료를 검증한다. */
    private void failureTicks(boolean firstFailure) {
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
                                lifecycle.tick();
                                if (firstFailure) verifyNoInteractions(cleanup);
                                lifecycle.tick();
                                assertThat(lifecycle.isRunning()).isTrue();
                                var order = inOrder(presence, cleanup);
                                if (firstFailure) {
                                    order.verify(presence, times(2)).cleanup(100);
                                } else {
                                    order.verify(presence).cleanup(100);
                                    order.verify(cleanup).cleanup(100);
                                    order.verify(presence).cleanup(100);
                                }
                                order.verify(cleanup).cleanup(100);
                                assertThat(events.list).hasSize(1);
                                assertThat(events.list.getFirst().getFormattedMessage())
                                        .isEqualTo("MEMBER_AUTH_CLEANUP_FAILED");
                                assertThat(events.list.getFirst().getThrowableProxy()).isNull();
                                lifecycle.stop();
                                lifecycle.stop();
                                lifecycle.tick();
                                verify(presence, times(2)).cleanup(100);
                                verify(cleanup, times(firstFailure ? 1 : 2)).cleanup(100);
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
                .withPropertyValues("app.member-auth.cleanup-enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            verifyNoInteractions(cleanup);
                        });
    }
}
