package com.stioc.cute.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面端凭证仓库单元测试。
 * <p>
 * 覆盖凭证生成强度、恒定时间比较语义与未激活态的 fail-closed 行为。
 * 凭证机制承载停机接口的鉴权，此前无任何测试覆盖，属安全链路上的盲区。
 * </p>
 * <p>
 * 测试不激活托管模式（不设置环境变量），因此不落任何文件、不污染真实配置目录；
 * 需要构造"已激活"状态时通过反射直接注入内存凭证字段。
 * </p>
 */
class DesktopTokenStoreTest {

    /**
     * 通过反射向 store 注入内存凭证，模拟"凭证机制已激活"状态。
     * <p>
     * 刻意不调用 init()：init 会读取环境变量并在托管模式下写盘，
     * 测试环境不应产出真实凭证文件。
     * </p>
     */
    private void injectToken(DesktopTokenStore store, String token) throws Exception {
        Field field = DesktopTokenStore.class.getDeclaredField("token");
        field.setAccessible(true);
        field.set(store, token);
    }

    @Nested
    @DisplayName("凭证生成强度")
    class TokenGenerationTests {

        @Test
        @DisplayName("生成的凭证为 43 字符 Base64url 无填充形态，熵不低于 256 位")
        void tokenFormatAndEntropy() throws Exception {
            DesktopTokenStore store = new DesktopTokenStore();
            // 直接调用私有生成方法，避免触发落盘与守护线程
            var method = DesktopTokenStore.class.getDeclaredMethod("generateToken");
            method.setAccessible(true);
            String token = (String) method.invoke(store);

            assertNotNull(token);
            assertEquals(43, token.length(), "32 字节 Base64url 无填充编码后固定为 43 字符");
            assertTrue(token.matches("^[A-Za-z0-9_-]+$"), "凭证应仅含 URL 安全字符，不含 + / =");

            // 解码回原始字节，确认实际强度为 32 字节（256 位）
            byte[] raw = Base64.getUrlDecoder().decode(token);
            assertEquals(32, raw.length, "凭证原始随机字节数应为 32 字节");
        }

        @Test
        @DisplayName("连续两次生成的凭证互不相同")
        void tokensAreRandom() throws Exception {
            DesktopTokenStore store = new DesktopTokenStore();
            var method = DesktopTokenStore.class.getDeclaredMethod("generateToken");
            method.setAccessible(true);
            String first = (String) method.invoke(store);
            String second = (String) method.invoke(store);

            assertNotEquals(first, second, "凭证必须来自加密安全随机源，不得重复");
        }
    }

    @Nested
    @DisplayName("凭证校验语义")
    class MatchesTests {

        @Test
        @DisplayName("未激活（内存凭证为空）时任何输入一律拒绝：fail-closed")
        void inactiveAlwaysRejects() {
            DesktopTokenStore store = new DesktopTokenStore();

            assertFalse(store.isActive());
            assertFalse(store.matches("anything"), "未激活状态必须拒绝所有凭证");
            assertFalse(store.matches(null));
            assertFalse(store.matches(""));
        }

        @Test
        @DisplayName("激活状态下仅完全一致的凭证通过")
        void onlyExactMatchPasses() throws Exception {
            DesktopTokenStore store = new DesktopTokenStore();
            String token = "test-token-value-for-verification";
            injectToken(store, token);

            assertTrue(store.isActive());
            assertTrue(store.matches(token), "完全一致应通过");
            assertFalse(store.matches(token + "x"), "超长子串不得通过");
            assertFalse(store.matches(token.substring(0, token.length() - 1)), "截断值不得通过");
            assertFalse(store.matches("TEST-TOKEN-VALUE-FOR-VERIFICATION"), "比较必须区分大小写");
            assertFalse(store.matches(null));
            assertFalse(store.matches(""));
        }

        @Test
        @DisplayName("凭证中含非 ASCII 字符时按 UTF-8 字节比较")
        void handlesNonAsciiToken() throws Exception {
            DesktopTokenStore store = new DesktopTokenStore();
            String token = "凭证-测试-值";
            injectToken(store, token);

            assertTrue(store.matches(token));
            assertFalse(store.matches("凭证-测试-别的值"));
        }
    }

    @Nested
    @DisplayName("凭证销毁幂等性")
    class DestroyTests {

        @Test
        @DisplayName("destroy 可重复调用且清空内存凭证")
        void destroyIsIdempotent() throws Exception {
            DesktopTokenStore store = new DesktopTokenStore();
            injectToken(store, "some-token");
            assertTrue(store.isActive());

            store.destroy();
            assertFalse(store.isActive(), "销毁后凭证必须不可用");
            // 重复调用不应抛异常（幂等语义）
            store.destroy();
            store.destroy();
            assertFalse(store.isActive());
        }

        @Test
        @DisplayName("销毁后原凭证立即失效")
        void destroyedTokenRejected() throws Exception {
            DesktopTokenStore store = new DesktopTokenStore();
            String token = "token-to-be-destroyed";
            injectToken(store, token);
            assertTrue(store.matches(token));

            store.destroy();
            assertFalse(store.matches(token), "销毁后即使凭证文本一致也必须拒绝");
        }
    }

    @Nested
    @DisplayName("凭证常量契约")
    class ConstantContractTests {

        @Test
        @DisplayName("凭证文件名与请求头名称为对外契约常量，须保持稳定")
        void publicConstantsStable() {
            // 权限沙箱排除凭证文件时依赖该常量，改名会导致排除逻辑与真实文件名脱节
            assertEquals(".desktop-token", DesktopTokenStore.TOKEN_FILE_NAME);
            assertEquals("X-Desktop-Token", DesktopTokenStore.TOKEN_HEADER);
            assertEquals(15, DesktopTokenStore.TOKEN_HEADER.getBytes(StandardCharsets.UTF_8).length);
        }
    }
}
