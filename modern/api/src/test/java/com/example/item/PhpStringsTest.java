package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PhpStringsTest {

    @Test
    @DisplayName("trim: PHP 기본 문자(공백 · \\t · \\n · \\r · \\0 · \\x0B)만 지우고 전각 공백은 남긴다")
    void trimsOnlyPhpWhitespace() {
        assertThat(PhpStrings.trim(" \t\n\r\0\013abc \n")).isEqualTo("abc");
        assertThat(PhpStrings.trim("　abc　")).isEqualTo("　abc　");
        assertThat(PhpStrings.trim(null)).isEmpty();
    }

    @Test
    @DisplayName("truncate: UTF-16 길이가 아니라 글자 수로 자른다")
    void truncatesByCodePoints() {
        String emoji = "😀";
        String text = emoji.repeat(3);

        assertThat(PhpStrings.length(text)).isEqualTo(3);
        assertThat(PhpStrings.truncate(text, 2)).isEqualTo(emoji.repeat(2));
        assertThat(PhpStrings.truncate("가나", 2)).isEqualTo("가나");
    }

    @Test
    @DisplayName("asciiLower: ASCII 대문자만 소문자로 바꾼다")
    void lowersAsciiOnly() {
        assertThat(PhpStrings.asciiLower("TiTLE")).isEqualTo("title");
        assertThat(PhpStrings.asciiLower("İD")).isEqualTo("İd");
    }

    @ParameterizedTest(name = "(int)\"{0}\" = {1}")
    @CsvSource({"5, 5", "05, 5", "+5, 5", "-1, -1", "5abc, 5", "abc, 0", "'', 0", "3.9, 3", "-3.9, -3", ".5, 0",
        "1e2, 100", "1e, 1", "0x1A, 0", "-, 0", "99999999999999999999, 2147483647",
        "-99999999999999999999, -2147483648", "1e999, 0"})
    @DisplayName("toInt: PHP 7.4 (int) 변환과 같게 앞부분 숫자만 읽는다")
    void convertsLikePhpIntCast(String input, int expected) {
        assertThat(PhpStrings.toInt(input)).isEqualTo(expected);
    }
}
