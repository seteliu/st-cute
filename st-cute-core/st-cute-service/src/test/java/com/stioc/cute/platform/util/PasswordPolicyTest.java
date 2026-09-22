package com.stioc.cute.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全访问码复杂度策略单元测试。
 * <p>覆盖长度区间（8~32）、字母与数字组成、字符白名单以及 validate 抛错语义。</p>
 */
class PasswordPolicyTest {

    @Nested
    @DisplayName("长度区间校验")
    class LengthTests {

        @ParameterizedTest(name = "合规长度 \"{0}\" 应通过")
        @ValueSource(strings = {"abcd1234", "abcd1234567890123456789012345678"})
        @DisplayName("恰好最小与最大长度边界应通过")
        void validLengthPass(String password) {
            assertTrue(PasswordPolicy.matches(password));
        }

        @Test
        @DisplayName("trim 后 8 位为最小合规长度（含首尾空格仍判定合规）")
        void trimBeforeCheck() {
            assertTrue(PasswordPolicy.matches("  abcd1234  "));
        }

        @ParameterizedTest(name = "不合规长度 \"{0}\" 应拒绝")
        @ValueSource(strings = {"abc123", "abc1234", "abcd12345678901234567890123456789"})
        @DisplayName("低于 8 位或超过 32 位一律拒绝")
        void invalidLengthReject(String password) {
            assertFalse(PasswordPolicy.matches(password));
        }
    }

    @Nested
    @DisplayName("组成与字符集校验")
    class CompositionTests {

        @ParameterizedTest(name = "缺少字母或数字的 \"{0}\" 应拒绝")
        @ValueSource(strings = {"12345678", "abcdefgh", "!@#$%^&*"})
        @DisplayName("必须同时包含英文字母与数字")
        void requireLetterAndDigit(String password) {
            assertFalse(PasswordPolicy.matches(password));
        }

        @ParameterizedTest(name = "中文等非 ASCII 字母的 \"{0}\" 应拒绝")
        @ValueSource(strings = {"密码12345678", "ＡＢＣＤ1234"})
        @DisplayName("刻意不使用 Character.isLetter：中文与全角字母不得视为字母")
        void rejectNonAsciiLetters(String password) {
            assertFalse(PasswordPolicy.matches(password));
        }

        @ParameterizedTest(name = "白名单内字符组合 \"{0}\" 应通过")
        @ValueSource(strings = {"abcd1234!", "abc_D-123", "abc 1234", "Pass@2026"})
        @DisplayName("常见半角符号与空格属允许字符")
        void allowedSymbolsPass(String password) {
            assertTrue(PasswordPolicy.matches(password));
        }

        @ParameterizedTest(name = "白名单外字符 \"{0}\" 应拒绝")
        @ValueSource(strings = {"abcd1234中", "abcd\u00E91234"})
        @DisplayName("白名单之外的非 ASCII 字符一律拒绝")
        void disallowedCharReject(String password) {
            assertFalse(PasswordPolicy.matches(password));
        }

        @Test
        @DisplayName("空值与 null 防御")
        void nullAndEmptyDefense() {
            assertFalse(PasswordPolicy.matches(null));
            assertFalse(PasswordPolicy.matches(""));
            assertFalse(PasswordPolicy.matches("        "));
        }
    }

    @Nested
    @DisplayName("validate 抛错语义")
    class ValidateTests {

        @Test
        @DisplayName("合规密码不抛异常")
        void validPasswordNoThrow() {
            PasswordPolicy.validate("abcd1234");
        }

        @Test
        @DisplayName("不合规密码抛出 BusinessException 且提示含策略描述")
        void invalidPasswordThrowWithDescription() {
            Exception e = assertThrows(Exception.class, () -> PasswordPolicy.validate("123"));
            assertTrue(e.getMessage().contains(PasswordPolicy.describe()));
        }

        @Test
        @DisplayName("策略描述包含长度区间口径")
        void describeContainsRange() {
            String desc = PasswordPolicy.describe();
            assertTrue(desc.contains(String.valueOf(PasswordPolicy.MIN_LENGTH)));
            assertTrue(desc.contains(String.valueOf(PasswordPolicy.MAX_LENGTH)));
        }
    }
}
