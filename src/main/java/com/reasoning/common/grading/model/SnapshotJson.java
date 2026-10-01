package com.reasoning.common.grading.model;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.reasoning.common.util.CommonUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 일반 JSON의 엄격한 파싱과 결정적 UTF-8 식별 바이트만 제공한다. 스냅샷·정책·REPORT 검증이나 해시 사전 이미지의 필드 선택을 수행하지 않는다. 문자열·배열
 * 순서·null을 보존하고 객체 키만 코드포인트 순으로 정렬한다.
 */
public final class SnapshotJson {
    private static final JsonFactory FACTORY =
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final String INVALID = "INVALID_SNAPSHOT_JSON";

    private SnapshotJson() {}

    /**
     * 중복 키와 후행 토큰을 거절하고 정수·소수를 BigInteger·BigDecimal로 정확히 읽는다. Jackson의 기본 구조 제약을 유지하며 문자열의
     * 공백·CR·LF·Unicode를 정규화하지 않는다.
     *
     * @param bytes null이 아닌 UTF-8 JSON이며 객체 이외의 루트도 허용한다
     * @return 입력 배열과 분리되어 새로 소유하는 트리이며 공유 불변 스칼라도 포함할 수 있다
     * @throws IllegalArgumentException null, 잘못된 JSON·UTF-8·서로게이트 또는 구조 제약 위반 시; 원문과 원인 예외를 포함하지 않는
     *     고정 메시지를 사용한다
     */
    public static JsonNode parse(byte[] bytes) {
        if (bytes == null) throw invalid();
        try {
            String decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            // 검증한 문자만 전달하여 Jackson이 원본 바이트를 UTF-16/32로 자동 재해석하지 못하게 한다.
            try (JsonParser parser = FACTORY.createParser(decoded)) {
                JsonNode result = read(parser, parser.nextToken());
                if (parser.nextToken() != null) throw invalid();
                return result;
            }
        } catch (CharacterCodingException exception) {
            throw invalid();
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * 객체 키를 재귀적으로 코드포인트 정렬하고 Jackson UTF-8 생성기로 compact JSON을 쓴다. 모든 수는 정확한 BigDecimal로 변환하여 0을
     * 통일하고 끝의 0을 제거한다. 조정 지수가 -6~20이면 일반 표기, 그 밖에는 BigDecimal 지수 표기를 사용하므로 거대한 지수를 일반 문자열로 확장하지
     * 않는다. 문자열은 정규화하지 않는다.
     *
     * @param node null이 아닌 일반 JSON 트리이며 호출자 트리를 변경하거나 보관하지 않는다
     * @return 호출할 때마다 새로 소유하는 결정적 UTF-8 바이트 배열
     * @throws IllegalArgumentException null, Missing·POJO·Binary 노드, 비유한 수, 잘못된 서로게이트 또는 Jackson 구조
     *     제약 위반 시; 민감한 원인 없이 고정 메시지를 사용한다
     */
    public static byte[] encode(JsonNode node) {
        if (node == null) throw invalid();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (JsonGenerator generator = FACTORY.createGenerator(output, JsonEncoding.UTF8)) {
                write(generator, node);
            }
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException | ArithmeticException exception) {
            throw invalid();
        }
    }

    /**
     * encode가 만드는 바로 그 바이트의 SHA-256을 계산하며 정책·투영을 선택하지 않는다.
     *
     * @param node null이 아닌 일반 JSON 트리
     * @return 소문자 64자리 해시
     * @throws IllegalArgumentException encode와 같은 입력 오류 시
     */
    public static String hash(JsonNode node) {
        return CommonUtil.sha256(encode(node));
    }

    /**
     * 저장 계층이 숫자 표시 변환 후 읽기 가능성을 검사할 때 실제 parser의 기존 한도를 제공한다. 한도를 새로 설정하거나 일반 수의 표현 범위를 줄이지 않는다.
     *
     * @return 현재 공유 Jackson parser의 숫자 길이 한도
     */
    public static int numberLengthLimit() {
        return FACTORY.streamReadConstraints().getMaxNumberLength();
    }

    private static JsonNode read(JsonParser parser, JsonToken token) throws IOException {
        if (token == null) throw invalid();
        return switch (token) {
            case START_OBJECT -> {
                ObjectNode object = JsonNodeFactory.instance.objectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid();
                    String key = unicode(parser.currentName());
                    object.set(key, read(parser, parser.nextToken()));
                }
                yield object;
            }
            case START_ARRAY -> {
                ArrayNode array = JsonNodeFactory.instance.arrayNode();
                JsonToken next;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                    array.add(read(parser, next));
                }
                yield array;
            }
            case VALUE_STRING -> TextNode.valueOf(unicode(parser.getText()));
            case VALUE_NUMBER_INT -> BigIntegerNode.valueOf(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> DecimalNode.valueOf(parser.getDecimalValue());
            case VALUE_TRUE -> BooleanNode.TRUE;
            case VALUE_FALSE -> BooleanNode.FALSE;
            case VALUE_NULL -> NullNode.instance;
            default -> throw invalid();
        };
    }

    private static void write(JsonGenerator generator, JsonNode node) throws IOException {
        if (node == null) throw invalid();
        switch (node.getNodeType()) {
            case OBJECT -> {
                generator.writeStartObject();
                List<String> keys = new ArrayList<>();
                node.fieldNames().forEachRemaining(key -> keys.add(unicode(key)));
                keys.sort(CommonUtil::compareCodePoints);
                for (String key : keys) {
                    generator.writeFieldName(key);
                    write(generator, node.get(key));
                }
                generator.writeEndObject();
            }
            case ARRAY -> {
                generator.writeStartArray();
                for (JsonNode element : node) write(generator, element);
                generator.writeEndArray();
            }
            case STRING -> generator.writeString(unicode(node.textValue()));
            case NUMBER -> {
                if ((node.isFloat() || node.isDouble()) && !Double.isFinite(node.doubleValue())) {
                    throw invalid();
                }
                BigDecimal number = node.decimalValue();
                if (number.signum() == 0) {
                    generator.writeNumber("0");
                } else {
                    number = number.stripTrailingZeros();
                    long exponent = (long) number.precision() - number.scale() - 1;
                    generator.writeNumber(
                            exponent >= -6 && exponent <= 20
                                    ? number.toPlainString()
                                    : number.toString());
                }
            }
            case BOOLEAN -> generator.writeBoolean(node.booleanValue());
            case NULL -> generator.writeNull();
            default -> throw invalid();
        }
    }

    private static String unicode(String value) {
        if (value == null) throw invalid();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw invalid();
                }
            } else if (Character.isLowSurrogate(character)) {
                throw invalid();
            }
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
