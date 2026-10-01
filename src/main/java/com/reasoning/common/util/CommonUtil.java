package com.reasoning.common.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 보안 키·복호화와 무관한 범용 값 변환만 제공한다. */
public final class CommonUtil {
    private CommonUtil() {}

    /**
     * 문자열을 UTF-16 코드 단위나 로케일이 아닌 Unicode 코드포인트 순으로 비교한다.
     *
     * @param left null이 아닌 왼쪽 문자열이며 정규화하지 않는다
     * @param right null이 아닌 오른쪽 문자열이며 정규화하지 않는다
     * @return 왼쪽이 작으면 음수, 같으면 0, 크면 양수
     * @throws NullPointerException 어느 문자열이 null인 경우
     */
    public static int compareCodePoints(String left, String right) {
        var a = Objects.requireNonNull(left).codePoints().iterator();
        var b = Objects.requireNonNull(right).codePoints().iterator();
        while (a.hasNext() && b.hasNext()) {
            int difference = Integer.compare(a.nextInt(), b.nextInt());
            if (difference != 0) return difference;
        }
        return Boolean.compare(a.hasNext(), b.hasNext());
    }

    /**
     * 입력 바이트를 변경하거나 보관하지 않고 SHA-256 소문자 16진수를 계산한다.
     *
     * @param value null이 아닌 원본 바이트이며 빈 배열도 허용한다
     * @return 소문자 64자리 해시
     * @throws NullPointerException value가 null인 경우
     * @throws IllegalStateException SHA-256 구현을 사용할 수 없는 경우
     */
    public static String sha256(byte[] value) {
        Objects.requireNonNull(value);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    /**
     * 올바른 Unicode 문자열의 줄바꿈을 LF로 통일하고 코드포인트 길이를 검사한다.
     *
     * @param input null이 아닌 원문이며 앞뒤 공백을 임의로 제거하지 않는다
     * @param max 허용할 최대 코드포인트 수이며 음수이면 모든 입력을 거절한다
     * @param blankAllowed true이면 빈 문자열·공백 전용 문자열도 허용한다
     * @return 줄바꿈만 정규화된 문자열
     * @throws IllegalArgumentException null, NUL, 비정상 surrogate, 길이 초과 또는 금지된 공백 입력 시
     */
    public static String normalizeText(String input, int max, boolean blankAllowed) {
        if (input == null || input.indexOf('\0') >= 0)
            throw new IllegalArgumentException("INVALID_TEXT");
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= input.length() || !Character.isLowSurrogate(input.charAt(++i)))
                    throw new IllegalArgumentException("INVALID_TEXT");
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("INVALID_TEXT");
            }
        }
        String value = input.replace("\r\n", "\n").replace('\r', '\n');
        if (value.codePointCount(0, value.length()) > max
                || !blankAllowed
                        && value.codePoints()
                                .allMatch(
                                        cp ->
                                                Character.isWhitespace(cp)
                                                        || Character.isSpaceChar(cp)))
            throw new IllegalArgumentException("INVALID_TEXT");
        return value;
    }
}
