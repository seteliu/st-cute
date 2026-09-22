package com.stioc.cute.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面端凭证过滤器单元测试。
 * <p>
 * 覆盖停机接口的强校验语义、探活接口的凭证状态标记，以及路径形变防御
 * （凭证校验必须基于容器解码后的路径，否则形变路径可绕过校验直达停机控制器）。
 * </p>
 */
class DesktopSecurityFilterTest {

    /**
     * 构造过滤器，并通过反射预设内存凭证（null 表示凭证机制未激活）
     */
    private DesktopSecurityFilter filterWithToken(String token) throws Exception {
        DesktopTokenStore store = new DesktopTokenStore();
        if (token != null) {
            Field field = DesktopTokenStore.class.getDeclaredField("token");
            field.setAccessible(true);
            field.set(store, token);
        }
        return new DesktopSecurityFilter(store);
    }

    /**
     * 执行过滤器，返回「是否到达后续过滤器链」
     */
    private boolean invoke(DesktopSecurityFilter filter, MockHttpServletRequest request,
                           MockHttpServletResponse response) throws Exception {
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse resp) {
                chained.set(true);
            }
        };
        filter.doFilter(request, response, chain);
        return chained.get();
    }

    /**
     * 构造携带解码后路径的请求
     */
    private MockHttpServletRequest request(String servletPath, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
        request.setRequestURI(servletPath);
        request.setServletPath(servletPath);
        return request;
    }

    @Nested
    @DisplayName("停机接口：凭证强校验")
    class ShutdownTests {

        @Test
        @DisplayName("凭证有效：放行至后续链路")
        void validTokenPasses() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            MockHttpServletRequest request = request("/api/shutdown", "POST");
            request.addHeader(DesktopTokenStore.TOKEN_HEADER, "secret-token");

            assertTrue(invoke(filter, request, new MockHttpServletResponse()),
                    "凭证匹配应放行");
        }

        @Test
        @DisplayName("凭证不匹配：403 拒绝且不进入后续链路")
        void wrongTokenRejected() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            MockHttpServletRequest request = request("/api/shutdown", "POST");
            request.addHeader(DesktopTokenStore.TOKEN_HEADER, "wrong-token");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(invoke(filter, request, response), "凭证不匹配必须拦截");
            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("未携带凭证头：403 拒绝")
        void missingTokenRejected() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(invoke(filter, request("/api/shutdown", "POST"), response));
            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("凭证机制未激活：一律 403 拒绝（fail-closed）")
        void inactiveStoreRejectsAll() throws Exception {
            DesktopSecurityFilter filter = filterWithToken(null);
            MockHttpServletRequest request = request("/api/shutdown", "POST");
            request.addHeader(DesktopTokenStore.TOKEN_HEADER, "anything");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(invoke(filter, request, response),
                    "未激活时不得因携带任意凭证而放行");
            assertEquals(403, response.getStatus());
        }
    }

    @Nested
    @DisplayName("探活接口：凭证可选")
    class PingTests {

        @Test
        @DisplayName("携带有效凭证：放行并标记 desktopAuth=required")
        void validTokenMarksRequired() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            MockHttpServletRequest request = request("/api/ping", "GET");
            request.addHeader(DesktopTokenStore.TOKEN_HEADER, "secret-token");

            assertTrue(invoke(filter, request, new MockHttpServletResponse()));
            assertEquals("required", request.getAttribute(DesktopSecurityFilter.PING_DESKTOP_AUTH_ATTR));
        }

        @Test
        @DisplayName("未携带凭证：放行并标记 desktopAuth=open")
        void missingTokenMarksOpen() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            MockHttpServletRequest request = request("/api/ping", "GET");

            assertTrue(invoke(filter, request, new MockHttpServletResponse()));
            assertEquals("open", request.getAttribute(DesktopSecurityFilter.PING_DESKTOP_AUTH_ATTR));
        }

        @Test
        @DisplayName("凭证不匹配：仍放行探测，但标记为 open")
        void wrongTokenMarksOpen() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            MockHttpServletRequest request = request("/api/ping", "GET");
            request.addHeader(DesktopTokenStore.TOKEN_HEADER, "wrong-token");

            assertTrue(invoke(filter, request, new MockHttpServletResponse()),
                    "探活接口不因凭证错误而拒绝");
            assertEquals("open", request.getAttribute(DesktopSecurityFilter.PING_DESKTOP_AUTH_ATTR));
        }
    }

    @Nested
    @DisplayName("范围与路径形变防御")
    class ScopeAndPathDefenseTests {

        @Test
        @DisplayName("非生命周期接口：原样放行且不参与凭证判定")
        void otherPathsPassThrough() throws Exception {
            DesktopSecurityFilter filter = filterWithToken(null);
            MockHttpServletRequest request = request("/api/config/list", "GET");

            assertTrue(invoke(filter, request, new MockHttpServletResponse()),
                    "非生命周期接口不应被本过滤器拦截");
        }

        @Test
        @DisplayName("形变路径 /api/x/../shutdown：规范化后判定，不得绕过凭证校验")
        void malformedPathStillGuarded() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            // 构造形变路径：规范化后等价于 /api/shutdown
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x/../shutdown");
            request.setRequestURI("/api/x/../shutdown");
            request.setServletPath("/api/x/../shutdown");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(invoke(filter, request, response),
                    "形变路径规范化后命中停机接口，必须仍受凭证保护");
            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("形变路径带有效凭证：规范化后正常放行")
        void malformedPathWithValidTokenPasses() throws Exception {
            DesktopSecurityFilter filter = filterWithToken("secret-token");
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x/../shutdown");
            request.setRequestURI("/api/x/../shutdown");
            request.setServletPath("/api/x/../shutdown");
            request.addHeader(DesktopTokenStore.TOKEN_HEADER, "secret-token");

            assertTrue(invoke(filter, request, new MockHttpServletResponse()));
        }
    }
}
