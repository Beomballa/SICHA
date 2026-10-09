package com.reasoning.common.member.auth;

import com.reasoning.common.auth.service.AuthProperties;
import com.reasoning.common.auth.service.BreachedPasswordChecker;
import com.reasoning.common.auth.service.CryptoService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemberAuthConfiguration.Properties.class)
public class MemberAuthConfiguration {
    @ConfigurationProperties(prefix = "app.member-auth")
    public static class Properties {
        private boolean collectionEnabled;
        private String envCode = "";
        private String mailFrom = "";
        private String evidenceRegistryFile = "";
        private boolean playtestCollectionEnabled;
        private String playtestEvidenceRegistryFile = "";

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
    public PlaytestPolicyGate playtestPolicyGate(JdbcTemplate db, Properties properties) {
        return new PlaytestPolicyGate(db, properties);
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
