package com.example.item;

import java.math.BigInteger;

/**
 * 레거시 문항 검색(PHP 7.4, {@code legacy/item-bank-php/search.php})의 문자열 처리를 그대로 흉내 내는 도우미.
 * 이관 중에는 레거시 동작을 바꾸지 않으므로, Java 표준 함수와 결과가 다른 부분을 여기서 맞춘다.
 */
final class PhpStrings {

    /** PHP {@code trim()} 이 지우는 문자: 공백, \t, \n, \r, \0, \x0B. U+3000 같은 유니코드 공백은 지우지 않는다. */
    private static final String TRIM_CHARS = " \t\n\r\0\013";

    /** PHP 의 문자열 → 정수 변환이 앞에서 건너뛰는 공백. */
    private static final String LEADING_WHITESPACE = " \t\n\r\013\f";

    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

    private PhpStrings() {
    }

    /** PHP {@code trim($s)}. null 은 빈 문자열로 본다. */
    static String trim(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && TRIM_CHARS.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM_CHARS.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /** PHP {@code mb_strlen($s, 'UTF-8')} — UTF-16 길이가 아니라 글자(code point) 수. */
    static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /** PHP {@code mb_substr($s, 0, $max, 'UTF-8')}. */
    static String truncate(String s, int maxLength) {
        if (length(s) <= maxLength) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxLength));
    }

    /** PHP {@code strtolower()} — ASCII 대문자만 바꾼다. */
    static String asciiLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? Character.toLowerCase(c) : c);
        }
        return sb.toString();
    }

    /**
     * PHP 7.4 {@code (int)$s}. 앞부분의 숫자만 읽는다.
     * 예: {@code "05"} → 5, {@code "+5"} → 5, {@code "5abc"} → 5, {@code "3.9"} → 3, {@code "1e2"} → 100,
     * {@code "abc"} → 0, {@code "-1"} → -1.
     * PHP 정수(64비트) 범위의 값을 int 범위로 자른다. 비교 대상 {@code item.level} 이 TINYINT 라 어느 쪽이든 일치하는 행이 없다.
     */
    static int toInt(String s) {
        int n = s.length();
        int i = 0;
        while (i < n && LEADING_WHITESPACE.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        int numberStart = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int digitsStart = i;
        i = skipDigits(s, i);
        boolean hasIntegerDigits = i > digitsStart;
        boolean isFloat = false;
        if (i < n && s.charAt(i) == '.') {
            int fractionEnd = skipDigits(s, i + 1);
            if (hasIntegerDigits || fractionEnd > i + 1) {
                isFloat = true;
                i = fractionEnd;
            }
        }
        if (!hasIntegerDigits && !isFloat) {
            return 0;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            int exponentEnd = skipDigits(s, j);
            if (exponentEnd > j) {
                isFloat = true;
                i = exponentEnd;
            }
        }
        String number = s.substring(numberStart, i);
        if (isFloat) {
            double value = Double.parseDouble(number);
            // PHP 7 은 무한대를 정수로 바꾸면 0 이다. 유한값은 0 쪽으로 버린다(Java 캐스트는 int 범위에서 멈춘다).
            return Double.isInfinite(value) ? 0 : (int) value;
        }
        return new BigInteger(number).max(INT_MIN).min(INT_MAX).intValue();
    }

    private static int skipDigits(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i;
    }
}
