package com.reasoning.web.member.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 명시적으로 활성화한 동일 출처 검증용 웹 자산만 제공한다. 인증 API 권한은 별도 체인에 남긴다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.member-web.enabled", havingValue = "true")
public class PlayerWebConfiguration implements WebMvcConfigurer {
    private final Path assets;

    /** assetsDirectory는 Flutter의 완성된 build/web 절대 경로이며 빈 값이면 자산 경로를 제공하지 않는다. */
    public PlayerWebConfiguration(
            @Value("${app.member-web.assets-directory:}") String assetsDirectory)
            throws IOException {
        if (assetsDirectory.isEmpty()) {
            assets = null;
            return;
        }
        Path path = Path.of(assetsDirectory);
        if (!path.isAbsolute() || Files.isSymbolicLink(path))
            throw new IllegalArgumentException("Invalid player web assets directory");
        assets = path.toRealPath();
        if (!Files.isDirectory(assets) || !Files.isRegularFile(assets.resolve("index.html")))
            throw new IllegalArgumentException("Incomplete player web assets directory");
    }

    /** 자산 GET/HEAD만 공개하고 HTTP·변경 요청은 거절한다. 관리자 체인·CSRF에는 영향이 없다. */
    @Bean
    @Order(-1)
    SecurityFilterChain playerWebAssetsSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/player/**")
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .rememberMe(remember -> remember.disable())
                .cors(cors -> cors.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                request ->
                                                        request.isSecure()
                                                                && (request.getMethod()
                                                                                .equals("GET")
                                                                        || request.getMethod()
                                                                                .equals("HEAD")))
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, failure) ->
                                                        response.sendError(403))
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        response.sendError(403)))
                .addFilterBefore(new AssetHeaders(), ExceptionTranslationFilter.class);
        return http.build();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (assets != null)
            registry.addViewController("/player/").setViewName("forward:/player/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (assets == null) return;
        String uri = assets.toUri().toString();
        registry.addResourceHandler("/player/**")
                .addResourceLocations(uri.endsWith("/") ? uri : uri + "/")
                .setCacheControl(CacheControl.noStore())
                .resourceChain(false)
                .addResolver(
                        new PathResourceResolver() {
                            @Override
                            protected Resource getResource(String resourcePath, Resource location)
                                    throws IOException {
                                Resource resource = super.getResource(resourcePath, location);
                                if (resource == null
                                        || !resource.getFile()
                                                .toPath()
                                                .toRealPath()
                                                .startsWith(assets)) return null;
                                return resource;
                            }
                        });
    }

    /** Flutter의 지역 WASM·스타일은 허용하되 외부 실행·연결·프레임·원문 캐시는 열지 않는다. */
    private static final class AssetHeaders extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'"
                        + " 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:;"
                        + " font-src 'self'; worker-src 'self' blob:; object-src 'none';"
                        + " frame-ancestors 'none'; base-uri 'self'; form-action 'none'");
            chain.doFilter(request, response);
        }
    }
}
