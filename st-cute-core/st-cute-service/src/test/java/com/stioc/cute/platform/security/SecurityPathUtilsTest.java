package com.stioc.cute.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 安全路径处理工具单元测试。
 * <p>核心保障：各类路径形变（百分号编码、反斜杠、点段、路径参数、多斜杠）经过统一规范化后
 * 必须收敛到同一形态，杜绝「过滤器判不中、Spring 路由却命中」的形变绕过。</p>
 */
class SecurityPathUtilsTest {

    @Nested
    @DisplayName("路径规范化")
    class NormalizeTests {

        @Test
        @DisplayName("常规路径保持不变")
        void normalPathUnchanged() {
            assertEquals("/api/shutdown", SecurityPathUtils.normalizePath("/api/shutdown"));
        }

        @ParameterizedTest(name = "形变路径 \"{0}\" 应归一为 \"{1}\"")
        @CsvSource({
                "/api/./shutdown, /api/shutdown",
                "/api//shutdown, /api/shutdown",
                "/api/x/../shutdown, /api/shutdown",
                "/api/shutdown/, /api/shutdown",
                "\\api\\shutdown, /api/shutdown",
                "/api/;jsessionid/shutdown, /api/shutdown",
                "/api/shutdown;jsessionid=abc, /api/shutdown"
        })
        @DisplayName("点段、多斜杠、反斜杠、路径参数统一归一")
        void deformedPathNormalized(String raw, String expected) {
            assertEquals(expected, SecurityPathUtils.normalizePath(raw));
        }

        @Test
        @DisplayName("越根上跳段被丢弃，保持 fail-close")
        void escapingSegmentsDropped() {
            assertEquals("/api/shutdown", SecurityPathUtils.normalizePath("/../../api/shutdown"));
        }

        @Test
        @DisplayName("根路径与空值边界")
        void rootAndNullBoundary() {
            assertEquals("/", SecurityPathUtils.normalizePath("/"));
            assertNull(SecurityPathUtils.normalizePath(null));
        }
    }

    @Nested
    @DisplayName("百分号解码兜底")
    class DecodeTests {

        @Test
        @DisplayName("URL 解码后的形变路径与直连形态归一一致")
        void decodedPathConverges() throws Exception {
            // 模拟 servletPath 为空、退回 requestURI 的场景：此时 URI 可能保留编码形态
            String encoded = "/api/%73hutdown";
            String decoded = java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("/api/shutdown", SecurityPathUtils.normalizePath(decoded));
        }
    }
}
