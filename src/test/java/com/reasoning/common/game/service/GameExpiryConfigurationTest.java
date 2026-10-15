package com.reasoning.common.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 합성 컨텍스트에서 실제 예약 수명·독립 활성화·비밀 없는 실패 재시도를 검사한다. */
class GameExpiryConfigurationTest {
    private final GamePlayService play = mock(GamePlayService.class);
    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(GameExpiryConfiguration.class)
                    .withBean(GamePlayService.class, () -> play);

    @Test
    void absentFalseAndNonServletNeverCreateOrInvokeExpiry() {
        runner.run(
                context -> {
                    assertThat(context)
                            .doesNotHaveBean(GameExpiryConfiguration.ExpiryLifecycle.class);
                    verifyNoInteractions(play);
                });
        runner.withPropertyValues(
                        "app.game.expiry-enabled=false",
                        "app.member-auth.collection-enabled=true",
                        "app.member-web.enabled=true",
                        "app.member-auth.cleanup-enabled=true")
                .run(
                        context -> {
                            assertThat(context)
                                    .doesNotHaveBean(GameExpiryConfiguration.ExpiryLifecycle.class);
                            verifyNoInteractions(play);
                        });
        new ApplicationContextRunner()
                .withUserConfiguration(GameExpiryConfiguration.class)
                .withBean(GamePlayService.class, () -> play)
                .withPropertyValues("app.game.expiry-enabled=true")
                .run(
                        context -> {
                            assertThat(context)
                                    .doesNotHaveBean(GameExpiryConfiguration.ExpiryLifecycle.class);
                            verifyNoInteractions(play);
                        });
    }

    @Test
    void explicitEnableOwnsOneDelayedDaemonAndCloseCancelsIt() {
        var owned = new GameExpiryConfiguration.ExpiryLifecycle[1];
        var scheduled = new ScheduledFuture<?>[1];
        var executors = new ScheduledThreadPoolExecutor[1];
        runner.withPropertyValues(
                        "app.game.expiry-enabled=true",
                        "app.member-auth.collection-enabled=false",
                        "app.member-web.enabled=false",
                        "app.member-auth.cleanup-enabled=false")
                .run(
                        context -> {
                            var lifecycle =
                                    context.getBean(GameExpiryConfiguration.ExpiryLifecycle.class);
                            owned[0] = lifecycle;
                            var executor =
                                    (ScheduledThreadPoolExecutor)
                                            ReflectionTestUtils.getField(lifecycle, "executor");
                            executors[0] = executor;
                            var future =
                                    (ScheduledFuture<?>)
                                            ReflectionTestUtils.getField(lifecycle, "future");
                            scheduled[0] = future;
                            assertThat(lifecycle.isRunning()).isTrue();
                            lifecycle.start();
                            assertThat(ReflectionTestUtils.getField(lifecycle, "executor"))
                                    .isSameAs(executor);
                            assertThat(executor.getQueue()).hasSize(1);
                            assertThat(future.getDelay(TimeUnit.SECONDS)).isBetween(1L, 60L);
                            assertThat(
                                            ((java.util.concurrent.RunnableScheduledFuture<?>)
                                                            future)
                                                    .isPeriodic())
                                    .isTrue();
                            Thread probe = executor.getThreadFactory().newThread(() -> {});
                            assertThat(probe.isDaemon()).isTrue();
                            assertThat(probe.getName()).isEqualTo("game-expiry");
                            verifyNoInteractions(play);
                            lifecycle.tick();
                            verify(play).settleExpired(100);
                        });
        assertThat(owned[0].isRunning()).isFalse();
        assertThat(scheduled[0].isCancelled()).isTrue();
        assertThat(executors[0].isShutdown()).isTrue();
        assertThat(executors[0].getQueue()).isEmpty();
        owned[0].stop();
        owned[0].tick();
        verify(play).settleExpired(100);
    }

    @Test
    void failureIsRedactedAndNextTickRetriesOnSameExecutor() {
        doThrow(new IllegalStateException("synthetic-secret-SQL-token"))
                .doReturn(1)
                .when(play)
                .settleExpired(100);
        Logger logger =
                (Logger) LoggerFactory.getLogger(GameExpiryConfiguration.ExpiryLifecycle.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            runner.withPropertyValues("app.game.expiry-enabled=true")
                    .run(
                            context -> {
                                var lifecycle =
                                        context.getBean(
                                                GameExpiryConfiguration.ExpiryLifecycle.class);
                                Object executor =
                                        ReflectionTestUtils.getField(lifecycle, "executor");
                                lifecycle.tick();
                                assertThat(lifecycle.isRunning()).isTrue();
                                lifecycle.tick();
                                assertThat(ReflectionTestUtils.getField(lifecycle, "executor"))
                                        .isSameAs(executor);
                                verify(play, times(2)).settleExpired(100);
                                assertThat(events.list).hasSize(1);
                                assertThat(events.list.getFirst().getFormattedMessage())
                                        .isEqualTo("GAME_EXPIRY_FAILED");
                                assertThat(events.list.getFirst().getThrowableProxy()).isNull();
                                lifecycle.stop();
                                lifecycle.stop();
                                lifecycle.tick();
                                verify(play, times(2)).settleExpired(100);
                            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void enabledServletContextRequiresGameService() {
        new WebApplicationContextRunner()
                .withUserConfiguration(GameExpiryConfiguration.class)
                .withPropertyValues("app.game.expiry-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
