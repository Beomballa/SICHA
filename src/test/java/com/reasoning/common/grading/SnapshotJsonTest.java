package com.reasoning.common.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.reasoning.common.grading.model.GradeDictionary;
import com.reasoning.common.grading.model.GradeDictionary.Term;
import com.reasoning.common.grading.model.SnapshotJson;
import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 합성 JSON의 바이트 식별만 검사하며 정책·GRADE·공개 승인의 근거를 만들지 않는다. */
class SnapshotJsonTest {
    @Test
    void nestedInsertionOrdersHaveFixedCanonicalBytesAndHash() {
        JsonNode first = parse("{\"b\":{\"y\":null,\"x\":true},\"a\":[3,2,1]}");
        JsonNode second = parse("{\"a\":[3,2,1],\"b\":{\"x\":true,\"y\":null}}");
        String expected = "{\"a\":[3,2,1],\"b\":{\"x\":true,\"y\":null}}";
        assertThat(SnapshotJson.encode(first)).isEqualTo(utf8(expected));
        assertThat(SnapshotJson.encode(second)).isEqualTo(utf8(expected));
        assertThat(SnapshotJson.hash(first))
                .isEqualTo("c7614bbe941680256d2b8eb1505a5ac977b8dc3be9542c43ed28cb3e57fcae29");
        assertThat(SnapshotJson.hash(second)).isEqualTo(SnapshotJson.hash(first));
    }

    @Test
    void supplementaryKeysSortAfterBmpPrivateUseAtEveryLevel() {
        JsonNode node = parse("{\"𐀀\":1,\"\uE000\":{\"𐀀\":2,\"\uE000\":3}}");
        assertThat(SnapshotJson.encode(node))
                .isEqualTo(
                        utf8(
                                "{\"\uE000\":{\"\uE000\":3,\"\\uD800\\uDC00\":2},\"\\uD800\\uDC00\":1}"));
        assertThat(CommonUtil.compareCodePoints("\uE000", "𐀀")).isNegative();
        assertThat(CommonUtil.compareCodePoints("가", "가나")).isNegative();
    }

    @Test
    void generatorEscapesControlsWithoutNormalizingStringsOrAstralText() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("s\"\\", " \"\\\b\f\n\r\t\0/𐀀가 ");
        String expected = "{\"s\\\"\\\\\":\" \\\"\\\\\\b\\f\\n\\r\\t\\u0000/\\uD800\\uDC00가 \"}";
        assertThat(SnapshotJson.encode(node)).isEqualTo(utf8(expected));
        assertThat(parse(expected).get("s\"\\").textValue())
                .isEqualTo(node.get("s\"\\").textValue());
        assertThat(SnapshotJson.encode(parse("\"é\"")))
                .isNotEqualTo(SnapshotJson.encode(parse("\"é\"")));
        assertThat(SnapshotJson.encode(parse("\"  a\\r\\nb\\rc\\n  \"")))
                .isEqualTo(utf8("\"  a\\r\\nb\\rc\\n  \""));
    }

    @Test
    void nullEmptyMissingAndArrayOrderRemainDistinct() {
        List<String> inputs =
                List.of("null", "\"\"", "[]", "{}", "{\"s\":null}", "{\"s\":\"\"}", "{\"s\":[]}");
        assertThat(inputs.stream().map(value -> SnapshotJson.hash(parse(value))).toList())
                .doesNotHaveDuplicates();
        assertThat(SnapshotJson.encode(parse("[null,{},[],\"\",2,1]")))
                .isEqualTo(utf8("[null,{},[],\"\",2,1]"));
        assertThat(SnapshotJson.hash(parse("[1,2]")))
                .isNotEqualTo(SnapshotJson.hash(parse("[2,1]")));
    }

    @Test
    void exactNumbersShareOneRecipeAndFixedHash() {
        JsonNode node =
                parse(
                        "[1e5,1000000000000000000000000000000,0.0000010,0.00000010,-0.00,-12.345678901234567890123456789]");
        String expected = "[100000,1E+30,0.000001,1E-7,0,-12.345678901234567890123456789]";
        assertThat(SnapshotJson.encode(node)).isEqualTo(utf8(expected));
        assertThat(SnapshotJson.hash(node))
                .isEqualTo("ec63ebb1ae22349f409fe307fde900bfc41c0c3a52b9d6340067a648fb165274");
        assertThat(node.get(1).bigIntegerValue())
                .isEqualTo(new BigInteger("1000000000000000000000000000000"));
        assertThat(node.get(5).decimalValue())
                .isEqualByComparingTo(new BigDecimal("-12.345678901234567890123456789"));
        assertThat(SnapshotJson.encode(parse("9007199254740993")))
                .isEqualTo(utf8("9007199254740993"));
    }

    @Test
    void equivalentIntegralDecimalAndExponentFormsHaveIdenticalBytes() {
        for (List<String> forms :
                List.of(
                        List.of("100000", "1e5", "100000.000"),
                        List.of("1e30", "1000000000000000000000000000000", "10e29"),
                        List.of("0", "-0", "-0.000", "0e50"),
                        List.of("-1.25", "-125e-2", "-1.2500"))) {
            byte[] expected = SnapshotJson.encode(parse(forms.getFirst()));
            for (String form : forms) {
                assertThat(SnapshotJson.encode(parse(form))).isEqualTo(expected);
            }
        }
    }

    @Test
    void exponentBoundariesAndHugeAstExponentsNeverRequirePlainExpansion() {
        assertThat(SnapshotJson.encode(parse("[1e-7,1e-6,1e20,1e21]")))
                .isEqualTo(utf8("[1E-7,0.000001,100000000000000000000,1E+21]"));
        assertThat(SnapshotJson.encode(DecimalNode.valueOf(new BigDecimal("1e1000000000"))))
                .isEqualTo(utf8("1E+1000000000"));
        assertThat(SnapshotJson.encode(DecimalNode.valueOf(new BigDecimal("-1e-1000000000"))))
                .isEqualTo(utf8("-1E-1000000000"));
        assertThat(
                        SnapshotJson.encode(
                                DecimalNode.valueOf(
                                        new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE))))
                .isEqualTo(utf8("1E+2147483648"));
    }

    @Test
    void parserRejectsDuplicatesTrailingTokensAndMalformedSyntaxWithoutCauses() {
        for (String invalid :
                List.of(
                        "",
                        " ",
                        "{\"secret\":1,\"secret\":2}",
                        "{\"x\":{\"a\":1,\"a\":2}}",
                        "[1] null",
                        "{} {}",
                        "[1,]",
                        "{\"x\":}",
                        "NaN",
                        "Infinity",
                        "01",
                        "/* comment */null")) {
            rejected(() -> parse(invalid));
        }
        rejected(() -> SnapshotJson.parse(null));
        rejected(() -> SnapshotJson.parse(new byte[] {'"', (byte) 0xC0, (byte) 0xAF, '"'}));
        rejected(() -> SnapshotJson.parse(new byte[] {'"', (byte) 0xE2, (byte) 0x82, '"'}));
    }

    /** UTF-8로 유효한 NUL 바이트도 다른 인코딩의 JSON으로 다시 해석하지 않는다. */
    @Test
    void parserRejectsBomlessUtf16AndUtf32InsteadOfAutodetectingThem() {
        for (byte[] invalid :
                List.of(
                        "{}".getBytes(StandardCharsets.UTF_16BE),
                        "{}".getBytes(StandardCharsets.UTF_16LE),
                        new byte[] {0, 0, 0, '{', 0, 0, 0, '}'},
                        new byte[] {'{', 0, 0, 0, '}', 0, 0, 0})) {
            rejected(() -> SnapshotJson.parse(invalid));
        }
        assertThat(SnapshotJson.encode(parse("{}"))).isEqualTo(utf8("{}"));
    }

    @Test
    void malformedSurrogatesInKeysAndValuesAreRejectedWithoutReplacement() {
        String high = String.valueOf((char) 0xD800);
        String low = String.valueOf((char) 0xDC00);
        for (String value : List.of(high, low, high + "a", low + high)) {
            rejected(() -> SnapshotJson.encode(TextNode.valueOf(value)));
            ObjectNode object = JsonNodeFactory.instance.objectNode().put(value, 1);
            rejected(() -> SnapshotJson.encode(object));
        }
        for (String json :
                List.of("\"\\uD800\"", "\"\\uDC00\"", "{\"\\uD800\":0}", "\"\\uD800x\"")) {
            rejected(() -> parse(json));
        }
        assertThat(SnapshotJson.encode(parse("\"\\uD800\\uDC00\"")))
                .isEqualTo(utf8("\"\\uD800\\uDC00\""));
    }

    @Test
    void unsupportedAndNonfiniteNodesAreRejectedEvenWhenNested() {
        List<JsonNode> invalid =
                List.of(
                        MissingNode.getInstance(),
                        new POJONode("secret"),
                        BinaryNode.valueOf(new byte[] {1}),
                        DoubleNode.valueOf(Double.NaN),
                        DoubleNode.valueOf(Double.POSITIVE_INFINITY),
                        DoubleNode.valueOf(Double.NEGATIVE_INFINITY),
                        FloatNode.valueOf(Float.NaN),
                        FloatNode.valueOf(Float.POSITIVE_INFINITY));
        for (JsonNode value : invalid) {
            rejected(() -> SnapshotJson.encode(value));
            rejected(() -> SnapshotJson.encode(JsonNodeFactory.instance.arrayNode().add(value)));
        }
        rejected(() -> SnapshotJson.encode(null));
        rejected(() -> SnapshotJson.hash(null));
        assertThat(SnapshotJson.encode(DoubleNode.valueOf(-1.25))).isEqualTo(utf8("-1.25"));
    }

    @Test
    void parsingAndRepeatedEncodingKeepIndependentOwnershipAndDoNotMutateTrees() {
        byte[] input = utf8("{\"z\":2,\"a\":1}");
        ObjectNode first = (ObjectNode) SnapshotJson.parse(input);
        ObjectNode second = (ObjectNode) SnapshotJson.parse(input);
        input[0] = '[';
        byte[] encoded = SnapshotJson.encode(first);
        byte[] repeated = SnapshotJson.encode(first);
        assertThat(repeated).isNotSameAs(encoded).isEqualTo(encoded);
        encoded[0] = '[';
        assertThat(SnapshotJson.encode(first)).isEqualTo(utf8("{\"a\":1,\"z\":2}"));
        assertThat(repeated).isEqualTo(utf8("{\"a\":1,\"z\":2}"));
        List<String> keys = new ArrayList<>();
        first.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("z", "a");
        first.put("a", 99);
        assertThat(SnapshotJson.encode(second)).isEqualTo(utf8("{\"a\":1,\"z\":2}"));
    }

    @Test
    void dictionaryKeepsItsRegisteredInsertionOrderedGoldenIdentity() {
        GradeDictionary dictionary =
                new GradeDictionary(
                        "DICT", List.of(new Term("Z", "다른", "별칭"), new Term("A", "기준", "표현")));
        String golden =
                "{\"formatNo\":1,\"dictionaryCode\":\"DICT\",\"terms\":[{\"conceptCode\":\"A\",\"canonical\":\"기준\",\"alias\":\"표현\"},{\"conceptCode\":\"Z\",\"canonical\":\"다른\",\"alias\":\"별칭\"}]}";
        assertThat(dictionary.canonicalJson()).isEqualTo(golden);
        assertThat(dictionary.sha256())
                .isEqualTo("54671299d8edc429d19ba88ba949e726aa04b74986ab3b2aa55aef988d54d1d1");
        assertThat(CommonUtil.sha256(utf8(golden))).isEqualTo(dictionary.sha256());
        assertThat(new GradeDictionary("DICT", dictionary.terms().reversed()).canonicalJson())
                .isEqualTo(golden);
        assertThat(SnapshotJson.encode(parse(golden))).isNotEqualTo(utf8(golden));
    }

    private static JsonNode parse(String json) {
        return SnapshotJson.parse(utf8(json));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_SNAPSHOT_JSON")
                .hasNoCause();
    }
}
