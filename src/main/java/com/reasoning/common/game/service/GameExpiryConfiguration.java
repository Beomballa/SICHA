package com.reasoning.common.game.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 인증 정리·회원 수집·웹 제공과 독립된 공개 게임 만료 실행을 명시 활성화한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GameExpiryConfiguration {
    /**
     * 명시 활성화한 서블릿 컨텍스트에만 만료 실행기를 생성한다.
     *
     * @param play 공개 방별 독립 거래를 소유하는 필수 서비스
     * @return 60초 초기·고정 지연으로 최대 100개 후보를 처리하는 실행기
     */
    @Bean
    @ConditionalOnProperty(name = "app.game.expiry-enabled", havingValue = "true")
    public ExpiryLifecycle gameExpiryLifecycle(GamePlayService play) {
        return new ExpiryLifecycle(play);
    }

    /** 간격은 실행 예산이며 보관 기간이나 종료 완료 시각을 보장하지 않는다. */
    public static final class ExpiryLifecycle implements SmartLifecycle {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(ExpiryLifecycle.class);
        private final GamePlayService play;
        private ScheduledThreadPoolExecutor executor;
        private ScheduledFuture<?> future;

        /**
         * @param play 만료 확정을 소유하며 null 불가인 게임 서비스
         */
        ExpiryLifecycle(GamePlayService play) {
            this.play = java.util.Objects.requireNonNull(play);
        }

        @Override
        public synchronized void start() {
            if (executor != null) return;
            executor =
                    new ScheduledThreadPoolExecutor(
                            1,
                            task -> {
                                Thread thread = Executors.defaultThreadFactory().newThread(task);
                                thread.setName("game-expiry");
                                thread.setDaemon(true);
                                return thread;
                            });
            executor.setRemoveOnCancelPolicy(true);
            future = executor.scheduleWithFixedDelay(this::tick, 60, 60, TimeUnit.SECONDS);
        }

        /** 실패 원문·예외·SQL을 노출하지 않고 다음 주기 재시도를 유지한다. */
        synchronized void tick() {
            if (!isRunning()) return;
            try {
                play.settleExpired(100);
            } catch (RuntimeException failure) {
                LOG.warn("GAME_EXPIRY_FAILED");
            }
        }

        @Override
        public synchronized void stop() {
            if (executor == null) return;
            future.cancel(false);
            executor.shutdownNow();
            future = null;
            executor = null;
        }

        @Override
        public synchronized boolean isRunning() {
            return executor != null && !executor.isShutdown();
        }
    }
}
