package com.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

/** 실제 공개 설정을 로드해 한글 이름과 접속값 필수 주입 경계를 검사한다. */
class PublicConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            new ConfigDataApplicationContextInitializer().initialize(context);
        });

    /** 호스트 접속값 없이 각 필수 설정 조회가 실패하며 로컬 기본값으로 연결되지 않아야 한다. */
    @Test
    void rejectsMissingDatabaseEnvironment() {
        runner.run(context -> {
            for (String property : new String[] {"url", "username", "password"}) {
                assertThatThrownBy(() -> context.getEnvironment().getProperty("spring.datasource." + property))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SPRING_DATASOURCE_" + property.toUpperCase(java.util.Locale.ROOT));
            }
        });
    }

    /** 합성 접속 설정만 주입했을 때 실제 설정 로더가 한글 브랜드와 세 접속값을 보존한다. */
    @Test
    void loadsBrandAndExplicitDatabaseEnvironment() {
        runner.withPropertyValues("SPRING_DATASOURCE_URL=jdbc:postgresql://database.invalid:6543/synthetic",
                "SPRING_DATASOURCE_USERNAME=synthetic_user", "SPRING_DATASOURCE_PASSWORD=synthetic_test_only")
            .run(context -> {
                var environment = context.getEnvironment();
                assertThat(environment.getProperty("spring.application.name")).isEqualTo("시차");
                assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://database.invalid:6543/synthetic");
                assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("synthetic_user");
                assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("synthetic_test_only");
            });
    }
}
