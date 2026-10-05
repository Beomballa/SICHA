package com.reasoning.common.migration;

import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;

import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 파일 탐색 없이 검증된 원본 SQL 전체를 기본 Flyway SQL 처리기에 제공한다. */
public final class EmbeddedSqlResourceProvider implements ResourceProvider {
    private final List<LoadableResource> resources;
    private final Map<String, LoadableResource> byName;

    /** 전체 원본의 이름·해시 검증이 성공한 경우에만 제공자를 구성한다. */
    public EmbeddedSqlResourceProvider() {
        resources =
                OriginalSqlCatalog.entries().stream()
                        .map(
                                entry ->
                                        (LoadableResource)
                                                new EmbeddedSqlResource(entry.name(), entry.sql()))
                        .toList();
        byName =
                resources.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        LoadableResource::getFilename, resource -> resource));
    }

    /**
     * 대소문자를 포함한 정확한 원본 이름으로 조회한다. 외부 자원으로 위임하지 않는다.
     *
     * @param name 원본 basename이며 null이 아니다
     * @return 일치하는 자원 또는 미등록 이름에 대한 null
     */
    @Override
    public LoadableResource getResource(String name) {
        return byName.get(Objects.requireNonNull(name, "name"));
    }

    /**
     * 접두사와 하나 이상의 접미사에 일치하는 자원을 중복 없이 반환한다.
     *
     * @param prefix null이 아닌 접두사; 빈 문자열은 전체 접두사와 일치한다
     * @param suffixes null이 아닌 접미사 배열; 빈 접미사는 모든 끝과 일치하며 빈 배열은 미일치다
     * @return 변경 불가능한 일치 자원 목록
     */
    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(suffixes, "suffixes");
        for (String suffix : suffixes) Objects.requireNonNull(suffix, "suffix");

        return resources.stream()
                .filter(resource -> resource.getFilename().startsWith(prefix))
                .filter(
                        resource ->
                                Arrays.stream(suffixes).anyMatch(resource.getFilename()::endsWith))
                .toList();
    }

    /** 원본 문자열을 소유하며 매 읽기마다 독립 reader를 만든다. 물리 파일은 존재하지 않는다. */
    static final class EmbeddedSqlResource extends LoadableResource {
        private final String name;
        private final String sql;

        /**
         * 논리 SQL 자원을 구성한다.
         *
         * @param name null이 아닌 SQL basename
         * @param sql null이 아닌 원본 SQL 전체 문자열
         */
        EmbeddedSqlResource(String name, String sql) {
            this.name = Objects.requireNonNull(name, "name");
            this.sql = Objects.requireNonNull(sql, "sql");
        }

        @Override
        public Reader read() {
            return new StringReader(sql);
        }

        @Override
        public String getFilename() {
            return name;
        }

        @Override
        public String getRelativePath() {
            return name;
        }

        @Override
        public String getAbsolutePath() {
            return "db/migration/" + name;
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return "";
        }
    }
}
