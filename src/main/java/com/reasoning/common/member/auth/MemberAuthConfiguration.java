package com.reasoning.common.member.auth;

import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.BreachedPasswordChecker;
import com.reasoning.common.auth.service.CryptoService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemberAuthConfiguration.Properties.class)
public class MemberAuthConfiguration {
    /** 게임 소유의 접속 근거 선행 정리를 실제 조립에서 요구하는 경계다. */
    @FunctionalInterface
    public interface PresenceCleanup {
        /**
         * 종료 family의 게임 접속 근거를 별도 소유 거래로 해제한다.
         *
         * @param limit 전체 갱신 상한 1~100
         * @return 확정된 접속 근거 해제 수
         * @throws RuntimeException 저장 또는 거래 소유권 검증 실패 시
         */
        int cleanup(int limit);
    }

    @ConfigurationProperties(prefix = "app.member-auth")
    public static class Properties {
        private boolean collectionEnabled;
        private boolean cleanupEnabled = false;
        private String envCode = "";
        private String mailFrom = "";
        private String evidenceRegistryFile = "";
        private boolean playtestCollectionEnabled;
        private String playtestEvidenceRegistryFile = "";
        private boolean friendlyCollectionEnabled;
        private String friendlyEvidenceRegistryFile = "";

        public boolean isCleanupEnabled() {
            return cleanupEnabled;
        }

        public void setCleanupEnabled(boolean value) {
            cleanupEnabled = value;
        }

        public boolean isFriendlyCollectionEnabled() {
            return friendlyCollectionEnabled;
        }

        public void setFriendlyCollectionEnabled(boolean value) {
            friendlyCollectionEnabled = value;
        }

        public String getFriendlyEvidenceRegistryFile() {
            return friendlyEvidenceRegistryFile;
        }

        public void setFriendlyEvidenceRegistryFile(String value) {
            friendlyEvidenceRegistryFile = value;
        }

        public boolean isPlaytestCollectionEnabled() {
            return playtestCollectionEnabled;
        }

        public void setPlaytestCollectionEnabled(boolean value) {
            playtestCollectionEnabled = value;
        }

        public String getPlaytestEvidenceRegistryFile() {
            return playtestEvidenceRegistryFile;
        }

        public void setPlaytestEvidenceRegistryFile(String value) {
            playtestEvidenceRegistryFile = value;
        }

        public boolean isCollectionEnabled() {
            return collectionEnabled;
        }

        public void setCollectionEnabled(boolean value) {
            collectionEnabled = value;
        }

        public String getEnvCode() {
            return envCode;
        }

        public void setEnvCode(String value) {
            envCode = value;
        }

        public String getMailFrom() {
            return mailFrom;
        }

        public void setMailFrom(String value) {
            mailFrom = value;
        }

        public String getEvidenceRegistryFile() {
            return evidenceRegistryFile;
        }

        public void setEvidenceRegistryFile(String value) {
            evidenceRegistryFile = value;
        }
    }

    /**
     * 예약 활성화와 무관하게 명시 호출 가능한 만료 정리 서비스를 조립한다.
     *
     * @param db 회원 저장소와 같은 연결 원천을 가진 템플릿
     * @return 자체 JDBC 거래를 소유한 정리 서비스
     */
    @Bean
    public MemberAuthCleanupService memberAuthCleanupService(JdbcTemplate db) {
        return new MemberAuthCleanupService(
                db,
                new DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(db.getDataSource())));
    }

    /**
     * 예약 활성화와 무관하게 같은 회원 연결 원천의 감사 만료 소비자를 조립한다.
     *
     * @param db 회원 저장소와 같은 연결 원천을 가진 템플릿
     * @return SQL을 실행하지 않고 구성한 감사·영수증 소유 거래 서비스
     * @throws NullPointerException 연결 원천이 없는 경우
     */
    @Bean
    public MemberAuthAuditCleanupService memberAuthAuditCleanupService(JdbcTemplate db) {
        return new MemberAuthAuditCleanupService(
                db,
                new DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(db.getDataSource())));
    }

    /**
     * 같은 연결 원천에서 회원 접근 이력과 최소 영수증의 소유 거래를 구성한다.
     *
     * @param db 회원 저장소와 같은 연결 원천의 템플릿이며 null 불가
     * @return 생성 중 SQL을 실행하지 않는 접근 이력 만료 서비스
     */
    @Bean
    public MemberAccessCleanupService memberAccessCleanupService(JdbcTemplate db) {
        return new MemberAccessCleanupService(
                db,
                new DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(db.getDataSource())));
    }

    @Configuration(proxyBeanMethods = false)
    public static class CleanupConfiguration {
        /**
         * 명시 활성화에만 정리 실행기를 생성하며 수집·웹 스위치는 참조하지 않는다.
         *
         * <p>접속 근거·인증 자료·제한 창·인증 감사·접근 이력과 영수증은 독립 확정되며 후속 실패가 앞 확정을 되돌리지 않는다.
         *
         * @param presence 게임 영역이 제공하는 필수 선행 정리
         * @param cleanup 종류별 100행 이하로 정리할 서비스
         * @param audit 감사와 만료 영수증 각각 100행 이하로 정리할 필수 서비스
         * @param access 회원 접근 이력과 만료 영수증 각각 100행 이하로 정리할 필수 서비스
         * @return 컨텍스트가 시작·종료하는 단일 스레드 실행기
         */
        @Bean
        @ConditionalOnProperty(name = "app.member-auth.cleanup-enabled", havingValue = "true")
        public CleanupLifecycle memberAuthCleanupLifecycle(
                PresenceCleanup presence,
                MemberAuthCleanupService cleanup,
                MemberAuthAuditCleanupService audit,
                MemberAccessCleanupService access) {
            return new CleanupLifecycle(
                    () -> {
                        presence.cleanup(100);
                        cleanup.cleanup(100);
                        audit.cleanup(100);
                        access.cleanup(100);
                    });
        }
    }

    /** 단일 스레드의 60초 고정 지연은 실행 간격이며 보관 기간을 변경하지 않는다. */
    public static final class CleanupLifecycle implements SmartLifecycle {
        private final Runnable cleanup;
        private java.util.concurrent.ScheduledExecutorService executor;
        private java.util.concurrent.ScheduledFuture<?> future;
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(CleanupLifecycle.class);

        /**
         * 컨텍스트 수명에 종속된 실행 책임을 구성한다.
         *
         * @param cleanup 비밀을 출력하지 않는 유한 정리 호출이며 null 불가
         */
        CleanupLifecycle(Runnable cleanup) {
            this.cleanup = java.util.Objects.requireNonNull(cleanup);
        }

        @Override
        public synchronized void start() {
            if (executor != null) return;
            executor =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                            task -> {
                                Thread thread = new Thread(task, "member-auth-cleanup");
                                thread.setDaemon(true);
                                return thread;
                            });
            future =
                    executor.scheduleWithFixedDelay(
                            this::tick, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        }

        /** 실패 원문·예외·SQL을 기록하지 않고 다음 주기의 재시도를 유지한다. */
        synchronized void tick() {
            if (!isRunning()) return;
            try {
                cleanup.run();
            } catch (RuntimeException failure) {
                LOG.warn("MEMBER_AUTH_CLEANUP_FAILED");
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

    @Bean
    public MemberMailTransport memberMailTransport(
            ObjectProvider<JavaMailSender> senders, Properties properties) {
        return new MemberMailTransport(senders, properties);
    }

    @Bean
    public MemberPolicyEvidenceRegistry memberPolicyEvidenceRegistry(
            Properties properties, AuthProperties keys, MemberMailTransport mail) {
        return new MemberPolicyEvidenceRegistry(properties, keys, mail);
    }

    @Bean
    public MemberPolicyGate memberPolicyGate(
            JdbcTemplate db, Properties properties, MemberPolicyEvidenceRegistry registry) {
        return new MemberPolicyGate(db, properties, registry);
    }

    @Bean
    @Primary
    public PlaytestPolicyGate playtestPolicyGate(JdbcTemplate db, Properties properties) {
        return new PlaytestPolicyGate(db, properties);
    }

    @Bean
    public PlaytestPolicyGate friendlyPolicyGate(JdbcTemplate db, Properties properties) {
        return PlaytestPolicyGate.friendly(db, properties);
    }

    @Bean
    public MemberAuthService memberAuthService(
            JdbcTemplate db,
            CryptoService crypto,
            BreachedPasswordChecker breached,
            MemberPolicyGate gate,
            MemberMailTransport mail) {
        return new MemberAuthService(
                db,
                new DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(db.getDataSource())),
                crypto,
                breached,
                gate,
                mail);
    }
}
