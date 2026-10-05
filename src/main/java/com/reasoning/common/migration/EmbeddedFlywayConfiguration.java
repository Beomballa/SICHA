package com.reasoning.common.migration;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Boot의 정상 load/migrate 이전에 SQL 공급원만 교체하며 초기화 수명과 설정은 유지한다. */
@Configuration(proxyBeanMethods = false)
public class EmbeddedFlywayConfiguration {

    /**
     * 스키마 저장소 의존 없이 완전한 원본 제공자를 정상 Flyway 구성에 등록한다.
     *
     * @return 기본 SQL resolver/parser/executor를 유지하는 구성 customizer
     * @throws IllegalStateException 원본 목록 또는 UTF-8 해시가 손상된 경우
     */
    @Bean
    FlywayConfigurationCustomizer embeddedSqlResources() {
        var provider = new EmbeddedSqlResourceProvider();
        return configuration -> configuration.resourceProvider(provider);
    }
}
