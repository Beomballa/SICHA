package com.reasoning.common.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reasoning.common.util.CommonUtil;

import org.junit.jupiter.api.Test;

class StoryTextTest {
    /** 코드포인트 상한을 UTF-16 길이와 혼동하지 않고 줄바꿈·공백 원문을 보존한다. */
    @Test
    void preservesUnicodeAndNormalizesOnlyNewlines() {
        assertThat(CommonUtil.normalizeText(" 𝄞가\r\n나\r ", 8, false)).isEqualTo(" 𝄞가\n나\n ");
        assertThat(CommonUtil.normalizeText("𝄞", 1, false)).isEqualTo("𝄞");
        assertThat(CommonUtil.normalizeText("", 0, true)).isEmpty();
        assertThat(CommonUtil.normalizeText("　", 1, true)).isEqualTo("　");
    }

    /** DB가 담을 수 없는 NUL·surrogate와 공백 전용 필수값을 저장 전에 거절한다. */
    @Test
    void rejectsInvalidStorageAndRequiredText() {
        for (String value :
                new String[] {
                    null,
                    "\0",
                    "",
                    "　",
                    "가나",
                    String.valueOf((char) 0xd800),
                    String.valueOf((char) 0xdc00)
                }) {
            assertThatThrownBy(() -> CommonUtil.normalizeText(value, 1, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
