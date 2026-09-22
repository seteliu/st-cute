package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.security.DesktopSecurityFilter;
import com.stioc.cute.platform.security.DesktopTokenStore;
import com.stioc.cute.platform.security.SecurityPathUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 应用生命周期管理控制器（供桌面端壳及探测工具调用）
 * <p>
 * 两个接口均处于 WebSecurityFilter 白名单内，由 {@link DesktopSecurityFilter} 实施内置凭证鉴权：
 * 凭证机制详见 {@link DesktopTokenStore}。桌面壳托管模式下 /api/shutdown 仅受理携带有效
 * 桌面端凭证的请求；非托管模式（IDE 开发）下凭证机制未激活，停机请求一律拒绝
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class LifecycleController {

    @Resource
    private Environment environment;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    @Resource
    private DesktopTokenStore desktopTokenStore;

    /**
     * 服务健康探测接口（凭证可选）
     * 用于桌面端壳启动时判断端口就绪状态及应用归属
     * <p>
     * 响应中的 desktopAuth 字段标识当前凭证状态：required=调用方携带了有效桌面端凭证
     * （桌面壳模式），open=开放探测（IDE 开发模式、attach 复用模式等）
     * </p>
     *
     * @param request 当前 HTTP 请求（从中读取 DesktopSecurityFilter 注入的凭证状态标记）
     * @return 包含应用标识、版本号与凭证状态的探测结果
     */
    @GetMapping("/ping")
    public Result<Map<String, String>> ping(HttpServletRequest request) {
        // DesktopSecurityFilter 未拦截到该请求时（理论不发生，白名单恒命中）默认按开放探测标记
        Object authMark = request.getAttribute(DesktopSecurityFilter.PING_DESKTOP_AUTH_ATTR);
        String desktopAuth = authMark != null ? authMark.toString() : "open";

        String version = environment.getProperty("st-cute.version", "unknown");
        Map<String, String> data = new HashMap<>();
        data.put("app", "st-cute");
        data.put("version", version);
        data.put("desktopAuth", desktopAuth);
        return Result.success(data);
    }

    /**
     * 优雅关闭服务接口（桌面端凭证强校验）
     * 仅当请求头携带与 DesktopTokenStore 内存凭证一致的 X-Desktop-Token 时允许触发，
     * 受理后立即销毁凭证防止重复停机，并异步延迟退出以保证响应先行送达
     * <p>
     * 安全兜底：不单纯信任 DesktopSecurityFilter 的前置校验结论，方法内自行复验当前
     * 请求路径与凭证，防御未来路由调整或过滤器链变更导致的形变路径绕过（纵深防御）
     * </p>
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应（复验失败时直接写出 403）
     * @return 操作响应
     */
    @PostMapping("/shutdown")
    public Result<String> shutdown(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 方法内自证路径与凭证：与 DesktopSecurityFilter 的判定逻辑保持一致。
        // 路径复核沿用公共规范化口径（解码 + 去分号参数 + 反斜杠归一 + 点段折叠），
        // 而非裸 endsWith("/shutdown")——后者对 "/api/x/../shutdown" 之类的形变路径判定更宽松，
        // 纵深防御不应比被防御对象更宽松
        String resolvedPath = SecurityPathUtils.resolvePath(request);
        if (!"/api/shutdown".equals(resolvedPath) || !desktopTokenStore.matches(
                request.getHeader(DesktopTokenStore.TOKEN_HEADER))) {
            log.warn("[Lifecycle] 收到未通过凭证复验的停机请求，已拒绝：URI = {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"Forbidden: 缺少有效的桌面端凭证\"}");
            return null;
        }

        log.info("[Lifecycle] 收到携带有效桌面端凭证的停机指令，销毁凭证并准备优雅停机...");

        // 先行销毁凭证：确保停机受理后凭证立即失效，杜绝停机窗口期被重复触发
        desktopTokenStore.destroy();

        // 异步延迟执行，确保能够先向客户端返回 200 OK 响应，避免客户端发生网络重置异常
        CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            log.info("[Lifecycle] 开始关闭 Spring ApplicationContext 并退出进程...");
            try {
                applicationContext.close();
            } catch (Exception e) {
                log.error("[Lifecycle] 关闭 Spring 上下文时发生异常", e);
            } finally {
                System.exit(0);
            }
        });

        return Result.success("st-cute 服务正在退出...");
    }

}
