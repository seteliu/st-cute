package com.stioc.cute.platform.security;

import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.contract.SecurityProperty;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 安全过滤器端到端行为测试。
 * <p>覆盖免密模式四重校验（对端 IP / XFF / Host / Origin）与已配置访问码时的同源校验，
 * 重点验证「本机其它端口网页」与「跨站来源」两类越权路径被拦截，同时确认正常访问不被误伤。</p>
 */
class WebSecurityFilterTest {

    /**
     * 免密模式过滤器：未配置访问码
     */
    private WebSecurityFilter passwordlessFilter() {
        ContractProperty contractProperty = new ContractProperty();
        contractProperty.setPassword(null);
        return new WebSecurityFilter(contractProperty, new SecurityProperty());
    }

    /**
     * 密码模式过滤器：已配置访问码
     */
    private WebSecurityFilter passwordFilter() {
        ContractProperty contractProperty = new ContractProperty();
        contractProperty.setPassword("$2a$fakeStoredValue");
        return new WebSecurityFilter(contractProperty, new SecurityProperty());
    }

    /**
     * 执行过滤器并返回响应，同时记录请求是否到达后续过滤器链
     */
    private boolean[] invoke(WebSecurityFilter filter, MockHttpServletRequest request,
                             MockHttpServletResponse response) throws Exception {
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse resp) {
                chained.set(true);
            }
        };
        filter.doFilter(request, response, chain);
        return new boolean[]{chained.get()};
    }

    /**
     * 构造本机对端来源的请求（默认携带合法 Host）
     */
    private MockHttpServletRequest localRequest(String uri, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:9661");
        return request;
    }

    @Nested
    @DisplayName("免密模式：四重校验")
    class PasswordlessModeTests {

        @Test
        @DisplayName("本机来源访问业务接口：放行并注入默认管理员")
        void localRequestAllowed() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/list", "GET");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean chained = invoke(passwordlessFilter(), request, response)[0];

            assertTrue(chained, "本机来源应放行");
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("非本机对端来源：401 拒绝（含白名单端点）")
        void remoteRequestRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/ping", "GET");
            request.setRemoteAddr("203.0.113.7");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean chained = invoke(passwordlessFilter(), request, response)[0];

            assertFalse(chained, "公网来源必须被拒绝");
            assertEquals(401, response.getStatus());
        }

        @Test
        @DisplayName("X-Forwarded-For 链路含非本机地址：401 拒绝")
        void untrustedXffRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/list", "GET");
            request.addHeader("X-Forwarded-For", "203.0.113.9, 127.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean chained = invoke(passwordlessFilter(), request, response)[0];

            assertFalse(chained, "XFF 链路中任一段非本机即应拒绝");
            assertEquals(401, response.getStatus());
        }

        @Test
        @DisplayName("Host 为 .local 域名：401 拒绝（mDNS 可被局域网设备指向回环）")
        void dotLocalHostRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/list", "GET");
            request.removeHeader("Host");
            request.addHeader("Host", "evil.local");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean chained = invoke(passwordlessFilter(), request, response)[0];

            assertFalse(chained, ".local 后缀不得作为本机形态放行");
            assertEquals(401, response.getStatus());
        }

        @Test
        @DisplayName("Host 为通配地址 0.0.0.0：401 拒绝")
        void anyLocalAddressHostRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/list", "GET");
            request.removeHeader("Host");
            request.addHeader("Host", "0.0.0.0:9661");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean chained = invoke(passwordlessFilter(), request, response)[0];

            assertFalse(chained, "通配地址不构成来源为本机的证据");
            assertEquals(401, response.getStatus());
        }

        @Test
        @DisplayName("Origin 为跨站来源：401 拒绝")
        void crossSiteOriginRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/list", "GET");
            request.addHeader("Origin", "https://evil.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean chained = invoke(passwordlessFilter(), request, response)[0];

            assertFalse(chained, "跨站 Origin 必须被拒绝");
            assertEquals(401, response.getStatus());
        }
    }

    @Nested
    @DisplayName("密码模式：同源校验")
    class PasswordModeTests {

        @Test
        @DisplayName("同源 POST 请求：通过校验（未登录返回 401，而非 403）")
        void sameOriginPostPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.addHeader("Origin", "http://localhost:9661");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "同源请求不应被跨源校验拦截");
        }

        @Test
        @DisplayName("无 Origin 的 POST 请求：放行（curl / 桌面壳 / IDE 场景）")
        void noOriginPostPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "非浏览器客户端不应被误伤");
        }

        @Test
        @DisplayName("本机其它端口网页发起 POST：403 拒绝（同站点跨源）")
        void otherLocalPortPostRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/conversation/cancel", "POST");
            request.addHeader("Origin", "http://localhost:9999");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(403, response.getStatus(), "同站点跨源页面必须被拒绝");
        }

        @Test
        @DisplayName("跨站 Origin 发起 POST：403 拒绝")
        void crossSitePostRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.addHeader("Origin", "https://evil.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("跨站页面发起 WebSocket 握手：403 拒绝")
        void crossSiteWebSocketRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/ws", "GET");
            request.addHeader("Origin", "https://evil.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(403, response.getStatus(), "WS 握手必须校验来源");
        }

        @Test
        @DisplayName("同源 WebSocket 握手：放行")
        void sameOriginWebSocketPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/ws", "GET");
            request.addHeader("Origin", "http://localhost:9661");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "同源 WS 握手不应被跨源校验拦截");
        }

        @Test
        @DisplayName("桌面壳固定来源 tauri.localhost：放行")
        void tauriOriginPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.addHeader("Origin", "http://tauri.localhost");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "桌面壳来源不应被拦截");
        }

        @Test
        @DisplayName("GET 请求不做同源限制（自适应任意访问地址）")
        void getRequestNotRestricted() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/list", "GET");
            request.addHeader("Origin", "https://my-own-domain.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "读取类请求不应被跨源校验拦截");
        }

        @Test
        @DisplayName("反代域名场景：Host 与 Origin 同为外部域名时放行")
        void reverseProxySameOriginPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.removeHeader("Host");
            request.addHeader("Host", "cute.example.com");
            request.addHeader("Origin", "https://cute.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "反代同源访问不应被误伤");
        }

        @Test
        @DisplayName("反代非默认端口：Host 与 Origin 端口一致时放行")
        void reverseProxyCustomPortPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.removeHeader("Host");
            request.addHeader("Host", "cute.example.com:8443");
            request.addHeader("Origin", "https://cute.example.com:8443");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "同端口反代访问不应被误伤");
        }

        @Test
        @DisplayName("局域网 IP 直连：Host 与 Origin 同源时放行")
        void lanIpSameOriginPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.removeHeader("Host");
            request.addHeader("Host", "192.168.1.100:9661");
            request.addHeader("Origin", "http://192.168.1.100:9661");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "局域网直连不应被误伤");
        }

        @Test
        @DisplayName("Vite 开发代理形态：Host 与 Origin 均为 9662 时放行")
        void devProxySameOriginPasses() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.removeHeader("Host");
            request.addHeader("Host", "localhost:9662");
            request.addHeader("Origin", "http://localhost:9662");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(401, response.getStatus(), "开发代理同源不应被误伤");
        }

        @Test
        @DisplayName("显式 null 来源：403 拒绝")
        void nullOriginRejected() throws Exception {
            MockHttpServletRequest request = localRequest("/api/config/save", "POST");
            request.addHeader("Origin", "null");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            assertEquals(403, response.getStatus(), "沙箱化来源按可疑处理");
        }
    }

    @Nested
    @DisplayName("路径形变防御")
    class PathDeformationTests {

        @Test
        @DisplayName("百分号编码形变路径经兜底解码后仍被正确识别")
        void encodedPathResolved() throws Exception {
            // servletPath 为空时退回 requestURI，编码形态必须解码后才参与判定
            MockHttpServletRequest request = localRequest("/api/%63onfig/save", "POST");
            request.addHeader("Origin", "https://evil.example.com");
            MockHttpServletResponse response = new MockHttpServletResponse();

            invoke(passwordFilter(), request, response);

            // 路径解码后为 /api/config/save（POST，非白名单）：跨源仍应 403
            assertEquals(403, response.getStatus(), "编码形变路径不得绕过来源校验");
        }
    }
}
