package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reasoning.common.grading.model.FrozenModelProjection;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.grading.service.FrozenDatasetValidator;
import com.reasoning.common.story.model.FrozenSnapshotCodec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 실제 격리 PostgreSQL JSONB 표시 변환에도 공통 식별 바이트가 유지되는지 확인한다. */
class SnapshotJsonIT {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void open() {
        // 타입 입출력 검증에는 업무 부모 행이나 BATCH 마이그레이션이 필요하지 않다.
        postgres =
                new PostgreSQLContainer<>(
                        DockerImageName.parse(
                                        "postgres:16.10@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1")
                                .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()));
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.close();
    }

    /** PostgreSQL이 소수·지수 표기를 바꿔도 정확한 수와 해시가 같아야 한다. */
    @Test
    void exactNumbersKeepIdentityThroughActualJsonbRepresentation() {
        for (String json :
                List.of(
                        "{\"n\":100000}",
                        "{\"n\":1e5}",
                        "{\"n\":1.00000e5}",
                        "{\"n\":-0.000}",
                        "{\"n\":1e30}",
                        "{\"n\":1000000000000000000000000000000}",
                        "{\"n\":123456789012345678901234567890123456789}",
                        "{\"n\":-0.123456789012345678901234567890123456789}",
                        "{\"n\":1e300}",
                        "{\"n\":1e-300}")) {
            assertRoundTrip(json);
        }
        assertThat(hash("{\"n\":100000}")).isEqualTo(hash("{\"n\":1.00000e5}"));
        assertThat(hash("{\"n\":1e30}")).isEqualTo(hash("{\"n\":1000000000000000000000000000000}"));
    }

    /** 키 재배치와 escape 변환은 허용하지만 배열·문자열·null 의미는 바꾸지 않는다. */
    @Test
    void unicodeWhitespaceAndArrayOrderKeepIdentityThroughActualJsonbRepresentation() {
        String json =
                "{\"𐀀\":\"🚀 한글\",\"\":\"é\","
                        + "\"fields\":{\"empty\":\"\",\"null\":null,\"text\":\" 앞\\r\\n뒤 \\t\"},"
                        + "\"items\":[{\"code\":\"B\"},{\"code\":\"A\"}]}";
        assertRoundTrip(json);
        assertThat(hash("{\"items\":[\"B\",\"A\"]}"))
                .isNotEqualTo(hash("{\"items\":[\"A\",\"B\"]}"));
        assertThat(hash("{\"text\":\" 앞\\r\\n뒤 \"}")).isNotEqualTo(hash("{\"text\":\" 앞\\n뒤 \"}"));
    }

    /** 실제 JSONB 출력에서 완전 사본의 다섯 식별과 선택 모델 입력이 모두 유지되는지 대조한다. */
    @Test
    void completeFrozenSnapshotKeepsAllIdentitiesThroughActualJsonb() {
        assertFrozenRoundTrip(FrozenSnapshotContractTest.complete());
    }

    /** 실제 JSONB 저장 가능 여부와 공유 parser 재읽기 가능 여부를 혼동하지 않는다. */
    @Test
    void frozenNumericBoundariesAreProvedAgainstActualJsonbAndSharedParser() {
        for (String number : List.of("1e999", "1e-1000", "-1e-1000", "0e-20000")) {
            ObjectNode root = FrozenSnapshotContractTest.complete();
            for (var sample : root.get("resources").get("gradeSamples")) {
                if ("INPUT".equals(sample.get("code").textValue())) {
                    var report = ((ObjectNode) sample.get("inputData")).putObject("report");
                    report.set("number", DecimalNode.valueOf(new BigDecimal(number)));
                }
            }
            assertFrozenRoundTrip(root);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT ?::jsonb::text", String.class, "1e-20000"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        for (String number : List.of("1e1000", "1e-1001")) {
            String jsonb = jdbc.queryForObject("SELECT ?::jsonb::text", String.class, number);
            assertThat(jsonb).isNotNull();
            assertThat(new BigDecimal(jsonb)).isEqualByComparingTo(new BigDecimal(number));
            assertThat(jsonb.length()).isGreaterThan(SnapshotJson.numberLengthLimit());
            assertThatThrownBy(() -> SnapshotJson.parse(jsonb.getBytes(StandardCharsets.UTF_8)))
                    .as("JSONB display for %s", number)
                    .hasMessage("INVALID_SNAPSHOT_JSON")
                    .hasNoCause();
        }
    }

    /** JSONB의 정수/소수 표기 변경과 잘못된 REPORT의 큰 수가 사본 의미를 수리하지 않아야 한다. */
    @Test
    void exactIntegralFieldsAndMalformedReportKeepIdentityThroughActualJsonb() {
        ObjectNode root = FrozenSnapshotContractTest.complete();
        var frozen = FrozenSnapshotCodec.freeze(root);
        ObjectNode wire = (ObjectNode) frozen.payload();
        wire.set("formatNo", DecimalNode.valueOf(new BigDecimal("1.000")));
        wire.set(
                "versionNo",
                DecimalNode.valueOf(new BigDecimal(wire.get("versionNo").intValue() + ".000")));
        String jsonb = jdbc.queryForObject("SELECT ?::jsonb::text", String.class, wire.toString());
        var restored = FrozenSnapshotCodec.decode(jsonb.getBytes(StandardCharsets.UTF_8));
        assertThat(restored.payloadBytes()).isEqualTo(frozen.payloadBytes());
        for (var sample : root.get("resources").get("gradeSamples")) {
            if ("INPUT".equals(sample.get("code").textValue())) {
                var malformed = ((ObjectNode) sample.get("inputData")).putObject("report");
                malformed.set("number", DecimalNode.valueOf(new BigDecimal("1e300")));
                malformed.put("text", " 앞\r\n🚀 뒤 ");
                malformed.putNull("method");
            }
        }
        assertFrozenRoundTrip(root);
    }

    /** 타입 변환만 실행하며 영구 행·인가·H3 사본 저장 완료로 표시하지 않는다. */
    private void assertFrozenRoundTrip(ObjectNode root) {
        var frozen = FrozenSnapshotCodec.freeze(root);
        var before = new FrozenDatasetValidator().validate(frozen);
        String jsonb =
                jdbc.queryForObject(
                        "SELECT ?::jsonb::text",
                        String.class,
                        new String(frozen.payloadBytes(), StandardCharsets.UTF_8));
        var restored = FrozenSnapshotCodec.decode(jsonb.getBytes(StandardCharsets.UTF_8));
        var after = new FrozenDatasetValidator().validate(restored);
        assertThat(restored.payloadBytes()).isEqualTo(frozen.payloadBytes());
        assertThat(restored.payloadHash()).isEqualTo(frozen.payloadHash());
        assertThat(restored.rubricHash()).isEqualTo(frozen.rubricHash());
        assertThat(restored.datasetHash()).isEqualTo(frozen.datasetHash());
        for (var fixture : root.get("resources").get("gradeSamples")) {
            var sample = before.select(fixture.get("code").textValue());
            assertThat(restored.inputHash(sample.code()))
                    .isEqualTo(frozen.inputHash(sample.code()));
            if (sample.report() != null) {
                var selected = after.select(sample.code());
                assertThat(restored.reportHash(selected.report()))
                        .isEqualTo(frozen.reportHash(sample.report()));
                assertThat(FrozenModelProjection.project(after, selected).payloadBytes())
                        .isEqualTo(FrozenModelProjection.project(before, sample).payloadBytes());
            } else {
                assertThat(after.select(sample.code()).report()).isNull();
            }
        }
    }

    /** 데이터나 영구 테이블을 만들지 않고 실제 JSONB 입출력의 바이트와 해시를 대조한다. */
    private void assertRoundTrip(String json) {
        var source = SnapshotJson.parse(json.getBytes(StandardCharsets.UTF_8));
        String persisted = jdbc.queryForObject("SELECT ?::jsonb::text", String.class, json);
        assertThat(persisted).isNotNull();
        var restored = SnapshotJson.parse(persisted.getBytes(StandardCharsets.UTF_8));
        assertThat(SnapshotJson.encode(restored)).isEqualTo(SnapshotJson.encode(source));
        assertThat(SnapshotJson.hash(restored)).isEqualTo(SnapshotJson.hash(source));
    }

    /** JSONB 출력 표기와 무관한 입력의 정확한 공통 식별 해시다. */
    private String hash(String json) {
        return SnapshotJson.hash(SnapshotJson.parse(json.getBytes(StandardCharsets.UTF_8)));
    }
}
