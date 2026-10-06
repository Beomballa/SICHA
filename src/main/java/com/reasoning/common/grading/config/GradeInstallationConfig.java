package com.reasoning.common.grading.config;

import com.reasoning.common.grading.engine.InstalledRuntimeManifestVerifier;
import com.reasoning.common.grading.repository.GradeDictionaryRepository;
import com.reasoning.common.grading.security.GradeWorkerCredentials;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/** 설치와 worker 자격증명을 별도로 고정하며 HTTP·worker 실행·스케줄러를 활성화하지 않는다. */
@Configuration(proxyBeanMethods = false)
public class GradeInstallationConfig {
    /**
     * DB 초기화와 무관하게 시작 시 전체 worker 등록을 검증하여 불변 단일 빈을 게시한다.
     *
     * @param path 보호 문서의 절대 경로이며 기본 빈 값·null·공백은 읽기 없이 모든 인증을 거절한다
     * @return 실제 등록과 소유자 증명을 고정한 자격증명 레지스트리
     * @throws IllegalStateException 설정된 입력 오류이면 원인 없는 INVALID_GRADE_WORKERS
     */
    @Bean
    @Lazy(false)
    public GradeWorkerCredentials gradeWorkerCredentials(
            @Value("${app.grading.workers-file:}") String path) {
        return new GradeWorkerRegistryLoader().load(path);
    }

    /**
     * 전체 설치를 성공한 경우에만 불변 레지스트리 빈을 게시한다.
     *
     * @param path 보호 문서의 절대 경로이며 null·공백은 비활성
     * @param jdbc 초기화가 끝난 DB 도구이며 비활성에서는 조회하지 않는다
     * @return configId별 실제 verifier의 불변 레지스트리
     * @throws IllegalStateException 원인·민감 값 없이 시작을 거절하는 설치 오류
     */
    @Bean
    @DependsOnDatabaseInitialization
    public Map<String, InstalledRuntimeManifestVerifier> gradeInstallations(
            @Value("${app.grading.installations-file:}") String path, JdbcTemplate jdbc) {
        return new GradeInstallationLoader().load(path, new GradeDictionaryRepository(jdbc));
    }
}
