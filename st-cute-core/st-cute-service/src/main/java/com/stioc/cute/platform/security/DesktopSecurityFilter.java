package com.stioc.cute.platform.security;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 桌面端生命周期接口凭证过滤器
 * <p>
 * 针对 WebSecurityFilter 白名单放行的 {@code /api/ping} 与 {@code /api/shutdown} 两个生命周期接口，
 * 实施先于主鉴权链路的内置凭证把关（见 {@link DesktopTokenStore} 凭证机制说明）：
 * <ul>
 *     <li>{@code /api/shutdown}：凭证强校验，未激活（无凭证存续）或凭证不匹配一律 403 拒绝</li>
 *     <li>{@code /api/ping}：凭证可选放行，携带有效凭证时向下游标记 desktopAuth=required（桌面壳模式），
 *     未携带则标记 desktopAuth=open（IDE 开发模式、attach 复用模式等开放探测场景）</li>
 * </ul>
 * 本过滤器必须先于 {@link WebSecurityFilter} 运行（Order 数值更小）：否则白名单请求会先被
 * WebSecurityFilter 直接放行而绕过凭证校验
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(DesktopSecurityFilter.DESKTOP_AUTH_ORDER)
public class DesktopSecurityFilter extends OncePerRequestFilter {

    /**
     * 本过滤器的执行顺序：必须严格先于 WebSecurityFilter（默认 Ordered.LOWEST_PRECEDENCE）执行
     */
    public static final int DESKTOP_AUTH_ORDER = Ordered.HIGHEST_PRECEDENCE;

    /**
     * 探活请求标记属性键：向下游 Controller 传递本次调用的凭证状态（required=桌面壳调用 / open=开放探测）
     */
    public static final String PING_DESKTOP_AUTH_ATTR = "desktopAuth";

    /**
     * 生命周期接口路径：与 WebSecurityFilter 白名单中的放行项保持一致
     */
    private static final String PATH_PING = "/api/ping";
    private static final String PATH_SHUTDOWN = "/api/shutdown";

    private final DesktopTokenStore tokenStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 路径取值与规范化统一走公共工具（与 WebSecurityFilter 同一判定口径）：
        // 必须基于容器解码后的 servletPath 而非原始 getRequestURI，否则形变路径（如 /api/%73hutdown）
        // 会导致过滤器判定与 Spring 实际路由不一致，攻击者可借此绕过凭证校验直达停机控制器
        String path = SecurityPathUtils.resolvePath(request);

        // 仅对两个生命周期接口生效，其余请求原样放行给后续过滤器链
        boolean isPing = PATH_PING.equals(path);
        boolean isShutdown = PATH_SHUTDOWN.equals(path);
        if (!isPing && !isShutdown) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean authorized = tokenStore.matches(request.getHeader(DesktopTokenStore.TOKEN_HEADER));

        if (isShutdown) {
            // 停机接口：凭证强校验，凭证机制未激活（从未生成）或凭证不匹配一律拒绝。
            // 拒绝日志刻意降为 debug 级别：本地端口存在公网扫描器随机探测的可能，避免日志刷屏
            if (!authorized) {
                log.debug("收到未授权的停机请求（未携带有效桌面端凭证），已拒绝");
                reject(response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 探活接口：凭证可选。携带有效凭证视为桌面壳在调用（desktopAuth=required），
        // 未携带则视为普通开放探测（IDE 开发模式、attach 复用模式），由调用方依据
        // 响应中的 desktopAuth 字段自行感知当前凭证状态
        request.setAttribute(PING_DESKTOP_AUTH_ATTR, authorized ? "required" : "open");
        filterChain.doFilter(request, response);
    }

    /**
     * 拒绝响应：与 WebSecurityFilter 的拒绝风格保持一致，返回 403 JSON 实体
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=utf-8");
        JSONObject errJson = new JSONObject();
        errJson.put("code", 403);
        errJson.put("msg", "Forbidden: 缺少有效的桌面端凭证");
        response.getWriter().write(errJson.toJSONString());
    }
}
