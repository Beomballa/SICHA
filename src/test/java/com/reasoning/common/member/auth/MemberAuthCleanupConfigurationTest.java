package com.reasoning.common.member.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
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
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MemberAuthConfiguration.CleanupConfiguration.class)
                    .withBean(MemberAuthCleanupService.class, () -> cleanup);

    @Test
    void absentAndExplicitFalseDoNotCreateOrInvokeScheduler() {
        runner.run(
                context -> {
                    assertThat(context)
                            .doesNotHaveBean(MemberAuthConfiguration.CleanupLifecycle.class);
                    verifyNoInteractions(cleanup);
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
                            verifyNoInteractions(cleanup);
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
                            verifyNoInteractions(cleanup);
                            owned[0].tick();
                            verify(cleanup).cleanup(100);
                        });
        assertThat(owned[0].isRunning()).isFalse();
        owned[0].tick();
        verify(cleanup, times(1)).cleanup(100);
    }

    @Test
    void storageFailureIsNonsecretAndDoesNotCancelFollowingTick() {
        Logger logger =
                (Logger) LoggerFactory.getLogger(MemberAuthConfiguration.CleanupLifecycle.class);
        var events = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        doThrow(new IllegalStateException("synthetic-secret-sql"))
                .doReturn(new MemberAuthCleanupService.Counts(0, 0, 0))
                .when(cleanup)
                .cleanup(100);
        try {
            runner.withPropertyValues("app.member-auth.cleanup-enabled=true")
                    .run(
                            context -> {
                                var lifecycle =
                                        context.getBean(
                                                MemberAuthConfiguration.CleanupLifecycle.class);
                                lifecycle.tick();
                                lifecycle.tick();
                                assertThat(lifecycle.isRunning()).isTrue();
                                verify(cleanup, times(2)).cleanup(100);
                                assertThat(events.list).hasSize(1);
                                assertThat(events.list.getFirst().getFormattedMessage())
                                        .isEqualTo("MEMBER_AUTH_CLEANUP_FAILED");
                                assertThat(events.list.getFirst().getThrowableProxy()).isNull();
                                lifecycle.stop();
                                lifecycle.stop();
                                lifecycle.tick();
                                verify(cleanup, times(2)).cleanup(100);
                            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
