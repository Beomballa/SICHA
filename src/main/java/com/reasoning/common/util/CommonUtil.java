package com.reasoning.common.util;

/** 보안 키·복호화와 무관한 범용 값 변환만 제공한다. */
public final class CommonUtil {
    private CommonUtil() {}

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
