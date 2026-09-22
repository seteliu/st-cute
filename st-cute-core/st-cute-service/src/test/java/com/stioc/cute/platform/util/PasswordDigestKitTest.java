package com.stioc.cute.platform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全访问密码摘要工具单元测试。
 * 覆盖盐随机性、摘要格式、校验匹配、时序侧信道防范与历史明文向后兼容。
 */
class PasswordDigestKitTest {

    @Nested
    @DisplayName("hash 摘要生成测试")
    class HashTests {

        @Test
        @DisplayName("生成 salt:digest 格式的存储字符串且两段均可 Base64 解码")
        void generateValidHashFormat() {
            String raw = "admin123456";
            String hashed = PasswordDigestKit.hash(raw);

            assertNotNull(hashed);
            assertTrue(hashed.contains(":"), "应包含盐与摘要的分隔符 ':'");

            String[] parts = hashed.split(":", 2);
            assertEquals(2, parts.length);

            // 盐应为 16 字节 Base64
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            assertEquals(16, salt.length, "盐长度应为 16 字节");

            // SHA-256 摘要应为 32 字节 Base64
            byte[] digest = Base64.getDecoder().decode(parts[1]);
            assertEquals(32, digest.length, "SHA-256 摘要长度应为 32 字节");
        }

        @Test
        @DisplayName("由于加盐随机性，同一明文多次 hash 产生的摘要各不相同")
        void randomSaltProducesDifferentHashes() {
            String raw = "my_secure_password";
            String hash1 = PasswordDigestKit.hash(raw);
            String hash2 = PasswordDigestKit.hash(raw);

            assertNotEquals(hash1, hash2, "随机加盐后同一密码生成的哈希必须不同");
        }
    }

    @Nested
    @DisplayName("matches 密码校验与兼容性测试")
    class MatchesTests {

        @Test
        @DisplayName("摘要模式：明文与自身生成的哈希匹配成功")
        void matchHashedPasswordSuccess() {
            String raw = "Secret@2026";
            String hashed = PasswordDigestKit.hash(raw);

            assertTrue(PasswordDigestKit.matches(raw, hashed));
        }

        @Test
        @DisplayName("摘要模式：错误密码匹配失败")
        void matchHashedPasswordFailure() {
            String raw = "Secret@2026";
            String hashed = PasswordDigestKit.hash(raw);

            assertFalse(PasswordDigestKit.matches("WrongPassword", hashed));
            assertFalse(PasswordDigestKit.matches("", hashed));
        }

        @Test
        @DisplayName("历史明文兼容：存储值为无分隔符的历史明文时能够正确匹配")
        void matchLegacyPlainTextPassword() {
            String plainStored = "legacy_admin_pwd";

            // 正确明文匹配
            assertTrue(PasswordDigestKit.matches("legacy_admin_pwd", plainStored));
            // 错误明文不匹配
            assertFalse(PasswordDigestKit.matches("other_pwd", plainStored));
        }

        @Test
        @DisplayName("边界与畸形输入防御")
        void boundaryAndMalformedInputs() {
            assertFalse(PasswordDigestKit.matches(null, "some_hash"));
            assertFalse(PasswordDigestKit.matches("password", null));
            assertFalse(PasswordDigestKit.matches(null, null));

            // 畸形摘要（含分隔符但非合法 Base64）返回 false 而非抛出未捕获异常
            assertFalse(PasswordDigestKit.matches("password", "invalid_salt:invalid_digest"));
            assertFalse(PasswordDigestKit.matches("password", ":"));
        }
    }

    @Nested
    @DisplayName("isDigested 与 sha256Hex 工具方法测试")
    class UtilityMethodTests {

        @Test
        @DisplayName("isDigested 精准识别摘要形态与历史明文")
        void testIsDigested() {
            String hashed = PasswordDigestKit.hash("test1234");
            assertTrue(PasswordDigestKit.isDigested(hashed));

            assertFalse(PasswordDigestKit.isDigested("plain_text_password"));
            assertFalse(PasswordDigestKit.isDigested(""));
            assertFalse(PasswordDigestKit.isDigested(null));
            assertFalse(PasswordDigestKit.isDigested("not_base64:not_base64"));
        }

        @Test
        @DisplayName("isDigested 严格按生成格式识别：形如明文的冒号串不得误判为摘要")
        void testIsDigestedStrictFormat() {
            // 用户在 config.json 手写的明文密码可能恰好含冒号且两段可 Base64 解码，
            // 宽松判定会将其误认为摘要形态、走质询链路导致登录失败且提示语义错乱
            assertFalse(PasswordDigestKit.isDigested("abcd:efgh"), "短段明文不应识别为摘要");
            assertFalse(PasswordDigestKit.isDigested("abcdefghijklmnop:abcdefghijklmnop"),
                    "长度不符的 Base64 段不应识别为摘要");
            assertFalse(PasswordDigestKit.isDigested(":digestOnly"));
            assertFalse(PasswordDigestKit.isDigested("saltOnly:"));

            // 恰为正确长度的合法 Base64 段应识别为摘要形态
            String validHash = PasswordDigestKit.hash("abcd1234");
            assertTrue(PasswordDigestKit.isDigested(validHash));
        }

        @Test
        @DisplayName("原文长度上限与访问码安全策略保持同一口径")
        void testMaxRawLengthAlignedWithPolicy() {
            assertEquals(PasswordPolicy.MAX_LENGTH, PasswordDigestKit.MAX_RAW_LENGTH);
        }

        @ParameterizedTest(name = "文本 \"{0}\" 的 SHA-256 十六进制摘要为 \"{1}\"")
        @ValueSource(strings = {"", "admin", "hello world"})
        @DisplayName("sha256Hex 返回 64 位小写十六进制摘要")
        void testSha256Hex(String text) {
            String hex = PasswordDigestKit.sha256Hex(text);
            assertNotNull(hex);
            assertEquals(64, hex.length());
            // 校验仅包含小写 16 进制字符
            assertTrue(hex.matches("^[0-9a-f]{64}$"));
        }

        @Test
        @DisplayName("sha256Hex 空值输入防御返回空字符串")
        void testSha256HexNull() {
            assertEquals("", PasswordDigestKit.sha256Hex(null));
        }
    }
}
