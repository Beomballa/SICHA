package com.reasoning.development;

import com.reasoning.common.auth.service.AuthException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;

/** 실행 전용 클래스패스에서만 소유한 개발 디렉터리·DB·브라우저 출처를 결속한다. */
public record DevelopmentState(Path home, URI origin) {
    /**
     * 소유자 전용 디렉터리와 루프백 HTTPS 원점만 받는다.
     *
     * @param home 현재 사용자가 소유한 0700 디렉터리이며 null 불가
     * @param origin 경로·쿼리·자격 정보 없는 localhost HTTPS 원점이며 명시 포트 필수
     */
    public DevelopmentState {
        try {
            if (home == null
                    || Files.isSymbolicLink(home)
                    || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getOwner(home, LinkOption.NOFOLLOW_LINKS)
                            .getName()
                            .equals(System.getProperty("user.name"))
                    || !Files.getPosixFilePermissions(home)
                            .equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IllegalStateException("개발 디렉터리 소유권 또는 권한이 올바르지 않습니다.");
            }
            home = home.toRealPath();
            if (origin == null
                    || !"https".equals(origin.getScheme())
                    || !"localhost".equals(origin.getHost())
                    || origin.getPort() < 1
                    || origin.getPort() > 65535
                    || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || !origin.getRawPath().isEmpty()) {
                throw new IllegalStateException("개발 출처는 명시 포트의 localhost HTTPS여야 합니다.");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("개발 디렉터리를 확인할 수 없습니다.");
        }
    }

    /**
     * 연결의 실제 메타데이터와 서버 DB·현재 역할을 검사한 후에만 개발 자료를 다룬다.
     *
     * @param jdbc 별도 개발 DataSource를 쓰는 템플릿이며 null 불가
     * @throws IllegalStateException 일반·운영 DB이거나 개발 역할과 일치하지 않는 경우
     */
    public void requireDatabase(JdbcTemplate jdbc) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            if (!connection
                    .getMetaData()
                    .getURL()
                    .matches(
                            "jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/sicha_development")) {
                throw new IllegalStateException("별도 루프백 개발 DB만 허용합니다.");
            }
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT current_database(), current_user")) {
                if (!rows.next()
                        || !"sicha_development".equals(rows.getString(1))
                        || !"sicha_development".equals(rows.getString(2))) {
                    throw new IllegalStateException("개발 DB 또는 역할이 일치하지 않습니다.");
                }
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("개발 DB 경계를 확인할 수 없습니다.");
        }
    }

    /**
     * 전달 헤더가 아닌 실제 루프백 요청과 정확한 HTTPS authority·변경 출처를 확인한다.
     *
     * @param request 실제 서블릿 요청이며 null 불가
     * @param mutation 계정 진입·자격 조회처럼 명시적 동일 출처 POST가 필요한 경우 true
     * @throws AuthException 출처·메서드·쿼리·루프백 조건을 만족하지 않는 경우
     */
    public void requireRequest(HttpServletRequest request, boolean mutation) {
        requireConnector(request);
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        if (request.getQueryString() != null
                || (mutation ? !"POST".equals(request.getMethod()) : !read)) {
            throw AuthException.forbidden("DEVELOPMENT_REQUEST_REJECTED");
        }
        var sites = Collections.list(request.getHeaders("Sec-Fetch-Site"));
        if (!sites.isEmpty()
                && (sites.size() != 1
                        || !("same-origin".equals(sites.getFirst())
                                || (!mutation && "none".equals(sites.getFirst()))))) {
            throw AuthException.forbidden("DEVELOPMENT_REQUEST_REJECTED");
        }
        var origins = Collections.list(request.getHeaders("Origin"));
        if ((mutation && (origins.size() != 1 || !origin.toString().equals(origins.getFirst())))
                || (!mutation
                        && !origins.isEmpty()
                        && (origins.size() != 1
                                || !origin.toString().equals(origins.getFirst())))) {
            throw AuthException.forbidden("DEVELOPMENT_REQUEST_REJECTED");
        }
    }

    /**
     * 기존 관리자 라우트의 자체 메서드·쿼리 검증과 별개로 실제 개발 connector만 확인한다.
     *
     * @param request 실제 서블릿 요청이며 null 불가
     * @throws AuthException 실제 루프백 HTTPS authority가 개발 원점과 다른 경우
     */
    public void requireConnector(HttpServletRequest request) {
        if (!request.isSecure()
                || !"https".equals(request.getScheme())
                || !"localhost".equals(request.getServerName())
                || request.getServerPort() != origin.getPort()
                || !"127.0.0.1".equals(request.getRemoteAddr())) {
            throw AuthException.forbidden("DEVELOPMENT_REQUEST_REJECTED");
        }
    }
}
