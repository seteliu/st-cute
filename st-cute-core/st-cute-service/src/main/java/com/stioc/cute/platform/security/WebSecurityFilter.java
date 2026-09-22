package com.stioc.cute.platform.security;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.platform.common.UserInfo;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.contract.SecurityProperty;
import com.stioc.cute.platform.util.UserUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Web 访问控制与身份鉴权过滤器
 * <p>未配置安全访问码（st-cute.password）时，仅允许本机来源访问（对端 IP + XFF 链路 + Host + Origin 四重校验）；
 * 配置后走密码登录鉴权，并对状态变更类请求与 WebSocket 握手施加同源校验（防同站点跨源页面驱动接口与劫持连接）</p>
 */
@Slf4j
@Component
public class WebSecurityFilter implements Filter {

    /**
     * 本机网卡地址集合缓存（惰性加载一次，网卡地址在运行期基本静态）
     */
    private volatile Set<InetAddress> localAddresses;

    private static final String[] WHITE_LIST = {
        // 登录接口
        "/api/auth/login",
        // 登录质询接口：与登录接口同为未登录态可达（质询值一次性 + 短时效，防重放）
        "/api/auth/challenge",
        // 登出接口：未登录态调用亦须放行（幂等登出，重复调用无副作用）
        "/api/auth/logout",
        // 以下两项由 DesktopSecurityFilter 实施内置凭证鉴权（详见 DesktopTokenStore）
        "/api/ping",
        "/api/shutdown"
    };

    /**
     * 免密模式（未配置访问码）下需要拦截的前缀
     */
    private static final String[] INTERCEPT_PREFIXES = {
        "/api",
        "/ws"
    };

    /**
     * 桌面壳 WebView 初始化阶段的固定来源：壳内 splash 页面的 origin，
     * 与后端地址天然不同源，属固定可信来源，硬编码放行（无需部署方配置）
     */
    private static final String TAURI_ORIGIN_HOST = "tauri.localhost";

    private final ContractProperty contractProperty;

    /**
     * 部署级安全配置（可信来源白名单等）
     */
    private final SecurityProperty securityProperty;

    /**
     * 构造方法注入配置属性
     *
     * @param contractProperty 配置属性
     * @param securityProperty 部署级安全配置
     */
    public WebSecurityFilter(ContractProperty contractProperty, SecurityProperty securityProperty) {
        this.contractProperty = contractProperty;
        this.securityProperty = securityProperty;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 路径取值与规范化统一走公共工具：与 DesktopSecurityFilter 保持同一判定口径
        String path = SecurityPathUtils.resolvePath(httpRequest);
        if (path == null) {
            path = "";
        }

        try {
            // 1. 尝试解析并注入用户信息到 ThreadLocal
            String requiredPassword = contractProperty.getPassword();
            if (!StringUtils.hasText(requiredPassword)) {
                // 未配置安全访问码：仅允许本机来源免密访问，非本机请求一律拒绝（含白名单端点，防止外网/内网探测与误关停）。
                // 返回 401 而非 403：未配置访问码即无登录凭据可校验，语义上属于"未获授权"，
                // 前端据此统一走会话失效流程（跳登录页），与"访问被禁止"的跨源拒绝区分开
                if (!isLocalOnlyRequest(httpRequest)) {
                    log.warn("系统未配置安全访问码，已拒绝非本机来源请求：对端 IP = {}，X-Forwarded-For = {}，Host = {}，Origin = {}，路径 = {}",
                            httpRequest.getRemoteAddr(), httpRequest.getHeader("X-Forwarded-For"),
                            httpRequest.getHeader("Host"), httpRequest.getHeader("Origin"), path);
                    writeJsonError(httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                            "系统未配置安全访问码，仅允许本机访问");
                    return;
                }
                // 本机来源：免密登录，直接注入默认管理员用户
                UserInfo defaultUser = new UserInfo("admin", "admin");
                UserUtils.setUser(defaultUser);
            } else {
                // 已配置密码：从 session 中尝试获取用户信息并注入
                HttpSession session = httpRequest.getSession(false);
                if (session != null) {
                    UserInfo userInfo = (UserInfo) session.getAttribute("user");
                    if (userInfo != null) {
                        UserUtils.setUser(userInfo);
                    }
                }
            }

            // 2. 已配置访问码时，对「状态变更类请求」与「WebSocket 握手」施加同源校验。
            // 该校验拦的是浏览器发起的跨源请求（如本机其它端口的网页借同站点 cookie 驱动接口、
            // 或跨站页面发起 WS 劫持），对 curl/桌面壳/IDE 等不携带 Origin 的客户端一律放行，
            // 因此不限制用户从任意域名、IP、端口（含反代与自签证书场景）正常访问
            if (StringUtils.hasText(requiredPassword) && !isSameOriginTrusted(httpRequest, path)) {
                log.warn("已拒绝跨源请求：对端 IP = {}，Host = {}，Origin = {}，路径 = {}，方法 = {}",
                        httpRequest.getRemoteAddr(), httpRequest.getHeader("Host"),
                        httpRequest.getHeader("Origin"), path, httpRequest.getMethod());
                writeJsonError(httpResponse, HttpServletResponse.SC_FORBIDDEN,
                        "跨源请求已被拒绝，请通过同源地址访问");
                return;
            }

            // 3. 如果已成功注入用户信息（已登录），则直接放行
            if (UserUtils.getUser() != null) {
                chain.doFilter(request, response);
                return;
            }

            // 4. 未登录状态下，若匹配白名单路由，放行（此时 ThreadLocal 中用户信息为空）
            for (String whiteUrl : WHITE_LIST) {
                if (whiteUrl.equals(path)) {
                    chain.doFilter(request, response);
                    return;
                }
            }

            // 5. 未登录状态下，若不匹配需要拦截的前缀（例如静态网页资源），放行
            boolean needFilter = false;
            for (String prefix : INTERCEPT_PREFIXES) {
                if (path.startsWith(prefix)) {
                    needFilter = true;
                    break;
                }
            }
            if (!needFilter) {
                chain.doFilter(request, response);
                return;
            }

            // 6. 既未登录，又不在白名单，且匹配拦截前缀：返回 401 状态码，并输出 JSON 实体对象
            writeJsonError(httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                    "未登录或登录已过期，请重新登录");

        } finally {
            // 7. 最终彻底清理当前线程的 ThreadLocal 信息，防止内存泄漏和线程复用污染
            UserUtils.clear();
        }
    }

    /**
     * 输出统一风格的 JSON 错误响应（与 DesktopSecurityFilter 的拒绝风格保持一致）
     *
     * @param response HTTP 响应对象
     * @param status   HTTP 状态码
     * @param message  面向用户的提示文案
     */
    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=utf-8");

        JSONObject errJson = new JSONObject();
        errJson.put("code", status);
        errJson.put("msg", message);
        response.getWriter().write(errJson.toJSONString());
    }

    /**
     * 判断请求是否来自本机（四重校验，仅在未配置安全访问码的免密模式下生效）
     * <p>1. TCP 对端地址必须为本机；2. 若经本机反向代理转发，X-Forwarded-For 链上任一地址判定为
     * 非本机均视为可疑并拒绝；3. Host 头须为本机形态（回环/本机 IP 字面量/localhost/本机计算机名），
     * 防御 DNS Rebinding；4. 浏览器语义的 Origin 头须为同源或本机形态，防御跨站 CSRF 与 WebSocket 跨站劫持</p>
     *
     * @param request 当前 HTTP 请求
     * @return true 表示四重校验全部通过，允许在未配置安全访问码时免密访问
     */
    private boolean isLocalOnlyRequest(HttpServletRequest request) {
        // 1. TCP 对端地址必须为本机，否则直接判定为外部来源
        String remoteAddr = request.getRemoteAddr();
        if (!StringUtils.hasText(remoteAddr) || !isLocalAddress(remoteAddr)) {
            return false;
        }

        // 2. 对端可信后，继续校验 X-Forwarded-For 链路（可能存在本机反向代理转发场景）
        Enumeration<String> xffHeaders = request.getHeaders("X-Forwarded-For");
        while (xffHeaders != null && xffHeaders.hasMoreElements()) {
            String xffValue = xffHeaders.nextElement();
            if (!StringUtils.hasText(xffValue)) {
                continue;
            }
            // 链路格式形如 client, proxy1, proxy2，逐段校验
            for (String segment : xffValue.split(",")) {
                String candidate = segment.trim();
                // unknown 为部分代理的占位值，不含真实来源信息，跳过
                if (!StringUtils.hasText(candidate) || "unknown".equalsIgnoreCase(candidate)) {
                    continue;
                }
                if (!isLocalAddress(candidate)) {
                    log.warn("X-Forwarded-For 链路中检测到非本机地址，判定为可疑来源：对端 IP = {}，XFF = {}", remoteAddr, xffValue);
                    return false;
                }
            }
        }

        // 3. Host 头必须为本机形态，阻断 DNS Rebinding（攻击域名解析到本机时 Host 为外部域名）
        if (!isTrustedHost(request.getHeader("Host"))) {
            log.warn("Host 头非本机形态，判定为可疑来源：对端 IP = {}，Host = {}", remoteAddr, request.getHeader("Host"));
            return false;
        }

        // 4. Origin 头须为同源或本机形态，阻断跨站 CSRF 与 WebSocket 跨站劫持
        if (!isTrustedOrigin(request)) {
            log.warn("Origin 头非本机同源，判定为可疑来源：对端 IP = {}，Origin = {}，Host = {}",
                    remoteAddr, request.getHeader("Origin"), request.getHeader("Host"));
            return false;
        }
        return true;
    }

    /**
     * 判断 Host 头是否为本机形态
     * <p>放行范围：localhost、本机 IP 字面量、本机计算机名（兼容 Windows 局域网以计算机名访问本机，
     * 仅字符串比对不发起 DNS）；其余（如外部域名、.local 等 mDNS 域名）一律拒绝</p>
     *
     * @param host Host 头原值（可能携带端口）
     * @return true 表示为本机形态
     */
    private boolean isTrustedHost(String host) {
        String hostname = extractHostname(host);
        if (hostname == null) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(hostname)) {
            return true;
        }
        // IP 字面量：须为本机地址
        if (isLocalAddress(hostname)) {
            return true;
        }
        // 本机计算机名比对（仅字符串比对，不发起 DNS 解析）
        try {
            if (hostname.equalsIgnoreCase(InetAddress.getLocalHost().getHostName())) {
                return true;
            }
        } catch (Exception e) {
            log.warn("获取本机计算机名失败，跳过计算机名比对", e);
        }
        // 刻意不放行 .local 后缀：mDNS 由局域网内任意设备应答，可把 *.local 指向 127.0.0.1
        // 从而同时满足「对端为本机 + Host 合法 + Origin 与 Host 一致」三项而绕过全部校验
        return false;
    }

    /**
     * 判断浏览器语义的 Origin 头是否可信
     * <p>放行范围：无 Origin（同源 GET 导航、curl 等非浏览器请求不携带）、tauri.localhost（桌面壳
     * WebView 初始化阶段的固定 Origin）、与本机同源的 Origin、本机形态的 Origin；
     * 显式 null（沙箱化来源）与其余外部 Origin 一律拒绝</p>
     *
     * @param request 当前 HTTP 请求
     * @return true 表示 Origin 可信
     */
    private boolean isTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        // 非浏览器请求或同源 GET 导航不携带 Origin，无法判定也无风险，放行
        if (!StringUtils.hasText(origin)) {
            return true;
        }
        origin = origin.trim();
        // 显式 null 为沙箱化文档来源（如 data: 页面发起的请求），按可疑处理
        if ("null".equalsIgnoreCase(origin)) {
            return false;
        }
        try {
            URI originUri = URI.create(origin);
            String scheme = originUri.getScheme();
            // 仅认可 http/https 语义
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String originHost = originUri.getHost();
            // 无法解析出主机（含非法字符等）按可疑处理
            if (originHost == null) {
                return false;
            }
            // 桌面壳 WebView 固定 Origin 放行（初始化阶段 webview 加载于 tauri.localhost 下）
            if ("tauri.localhost".equalsIgnoreCase(originHost)) {
                return true;
            }
            // 与 Host 头同主机（真实同源）
            String hostHeader = request.getHeader("Host");
            String hostHeaderName = extractHostname(hostHeader);
            if (hostHeaderName != null && originHost.equalsIgnoreCase(hostHeaderName)) {
                // 主机名相同还须端口一致：同站点不同端口属跨源（SameSite cookie 只比站点不比端口，
                // 本机其它端口的任意网页仍会携带会话 cookie 并可直接发起 WS 握手劫持）
                int hostPort = extractPort(hostHeader, originUri.getScheme());
                int originPort = originUri.getPort() < 0 ? defaultPort(originUri.getScheme()) : originUri.getPort();
                if (hostPort == originPort) {
                    return true;
                }
                // 端口不一致即明确判为跨源：此处必须直接拒绝，
                // 不得落入下方的本机形态兜底分支——该分支放行 127.0.0.1 等回环字面量，
                // 会让本机其它端口网页借同一台机器的身份绕过校验，端口校验形同虚设
                log.warn("Origin 与 Host 主机名相同但端口不一致，判定为跨源：Origin = {}，Host = {}", origin, hostHeader);
                return false;
            }
            // 本机形态的 Origin（回环 / localhost / 本机网卡地址）
            return isLocalAddress(originHost);
        } catch (Exception e) {
            // Origin 解析异常按安全兜底策略处理：视为可疑来源并拒绝
            log.warn("解析 Origin 头失败，按可疑来源处理：{}", origin, e);
            return false;
        }
    }

    /**
     * 同源校验（仅在已配置安全访问码时生效）：判定浏览器发起的请求是否来自可信来源
     * <p>
     * 校验目的：拦截「同站点但跨源」的浏览器请求与本机其它端口网页的越权驱动
     * （SameSite cookie 只比较站点、不比较端口，故同站点跨源请求仍会携带会话 cookie），
     * 以及跨站页面发起的 WebSocket 握手劫持（WS 握手不受 CORS 约束）。
     * </p>
     * <p>
     * 判定顺序（任一命中即放行）：
     * <ol>
     *     <li>无 Origin：curl / 桌面壳 / IDE 等非浏览器客户端不携带该头，无法判定也无风险，放行</li>
     *     <li>仅对状态变更类请求与 WebSocket 握手校验（读取类 GET 不做限制，避免误伤）</li>
     *     <li>Origin 为显式 null（沙箱化来源）一律拒绝</li>
     *     <li>Origin 与 Host 同源（主机名 + 端口一致，不看 scheme）：自适应任意域名/IP/端口，
     *     裸 HTTP、自签证书、HTTPS 反代三种部署形态均无需额外配置</li>
     *     <li>命中内置的桌面壳固定来源或部署级可信来源白名单</li>
     * </ol>
     * 因判定依据是「Origin 与 Host 是否一致」而非固定域名白名单，
     * 用户从任意地址（含反代域名、局域网 IP、自定义端口）的正常访问均不受限制。
     * </p>
     *
     * @param request 当前 HTTP 请求
     * @param path    规范化后的请求路径
     * @return true 表示可信（放行）
     */
    private boolean isSameOriginTrusted(HttpServletRequest request, String path) {
        String origin = request.getHeader("Origin");
        // 1. 非浏览器请求或同源 GET 导航不携带 Origin，无法判定也无风险，放行
        if (!StringUtils.hasText(origin)) {
            return true;
        }
        // 2. 仅校验状态变更类请求与 WebSocket 握手：GET 等读取类请求不改状态，不做限制
        if (!isCrossSiteGuardTarget(request, path)) {
            return true;
        }
        origin = origin.trim();
        // 3. 显式 null 为沙箱化文档来源（如 data: 页面发起的请求），按可疑处理
        if ("null".equalsIgnoreCase(origin)) {
            return false;
        }
        try {
            URI originUri = URI.create(origin);
            String scheme = originUri.getScheme();
            // 仅认可 http/https 语义（其他 scheme 可能为 file: 等本地协议，按可疑处理）
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String originHost = originUri.getHost();
            // 无法解析出主机（含非法字符等）按可疑处理
            if (originHost == null) {
                return false;
            }
            // 4. 桌面壳 WebView 初始化阶段的固定来源（不受端口与协议影响）
            if (TAURI_ORIGIN_HOST.equalsIgnoreCase(originHost)) {
                return true;
            }
            // 5. 与 Host 头同源（主机名与端口同时一致，视为真实同源）
            if (isSameOrigin(request, originUri)) {
                return true;
            }
            // 6. 部署级可信来源白名单
            if (isInTrustedOrigins(originHost, originUri.getPort())) {
                return true;
            }
            log.warn("Origin 与 Host 不同源，且不在可信来源白名单内：Origin = {}，Host = {}",
                    origin, request.getHeader("Host"));
            return false;
        } catch (Exception e) {
            // Origin 解析异常按安全兜底策略处理：视为可疑来源并拒绝
            log.warn("解析 Origin 头失败，按可疑来源处理：{}", origin, e);
            return false;
        }
    }

    /**
     * 判定请求是否属于跨源防护目标：状态变更类请求（非 GET/HEAD/OPTIONS）或 WebSocket 握手
     *
     * @param request 当前 HTTP 请求
     * @param path    规范化后的请求路径
     * @return true 表示需要施加同源校验
     */
    private boolean isCrossSiteGuardTarget(HttpServletRequest request, String path) {
        // WebSocket 握手：不受 CORS 约束，且是唯一能读走会话事件的通道，必须校验
        if (path.startsWith("/ws")) {
            return true;
        }
        String method = request.getMethod();
        if (method == null) {
            return false;
        }
        // 读取类方法不改状态，不做同源限制
        return !"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method);
    }

    /**
     * 判断 Origin 是否与请求的 Host 头同源（主机名忽略大小写，端口须一致）
     * <p>不对 scheme 做比较：Host 头本身不携带 scheme，无法与 Origin 比对，
     * 且不放行 scheme 也为「同时接收 HTTP 与 HTTPS」的部署留出空间</p>
     *
     * @param request    当前 HTTP 请求
     * @param originUri  Origin 解析出的 URI
     * @return true 表示同源
     */
    private boolean isSameOrigin(HttpServletRequest request, URI originUri) {
        String hostHeader = request.getHeader("Host");
        if (!StringUtils.hasText(hostHeader)) {
            return false;
        }
        String hostName = extractHostname(hostHeader);
        if (hostName == null || !hostName.equalsIgnoreCase(originUri.getHost())) {
            return false;
        }
        // 端口双向归一化后比对：Host 与 Origin 任一方未显式给出端口时，均按其协议默认端口取值。
        // 若只归一化 Host 一侧，https 反代下 Origin 的 "https://domain.com"（无端口，getPort() 返回 -1）
        // 与 Host 的 "domain.com"（无端口，归一为 443）会被误判为跨源，导致正常访问被 403 拒绝
        int hostPort = extractPort(hostHeader, originUri.getScheme());
        int originPort = originUri.getPort() < 0 ? defaultPort(originUri.getScheme()) : originUri.getPort();
        return hostPort == originPort;
    }

    /**
     * 判断来源是否命中部署级可信来源白名单
     * <p>支持「主机名」与「主机名:端口」两种条目写法；不带端口的条目对任意端口生效</p>
     *
     * @param host 来源主机名
     * @param port 来源端口（-1 表示 Origin 未显式给出端口）
     * @return true 表示命中白名单
     */
    private boolean isInTrustedOrigins(String host, int port) {
        List<String> trustedOrigins = securityProperty.getTrustedOrigins();
        if (trustedOrigins == null || trustedOrigins.isEmpty()) {
            return false;
        }
        for (String entry : trustedOrigins) {
            if (!StringUtils.hasText(entry)) {
                continue;
            }
            String item = entry.trim();
            int colon = item.lastIndexOf(':');
            if (colon > 0) {
                // 带端口条目：端口须完全一致
                String entryHost = item.substring(0, colon);
                String entryPort = item.substring(colon + 1);
                if (entryHost.equalsIgnoreCase(host) && String.valueOf(port).equals(entryPort)) {
                    return true;
                }
                continue;
            }
            if (item.equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 Host 头中提取端口，未显式给出时按 scheme 返回默认端口
     *
     * @param host   Host 头原值（可能携带端口）
     * @param scheme 协议名（http/https）
     * @return 端口号
     */
    private int extractPort(String host, String scheme) {
        String value = host.trim();
        if (value.startsWith("[")) {
            // IPv6 形态 [::1]:9661
            int end = value.indexOf(']');
            if (end > 0 && value.length() > end + 2 && value.charAt(end + 1) == ':') {
                return parsePort(value.substring(end + 2), scheme);
            }
            return defaultPort(scheme);
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            return parsePort(value.substring(colon + 1), scheme);
        }
        return defaultPort(scheme);
    }

    /**
     * 解析端口字符串，非法值回退为 scheme 默认端口
     */
    private int parsePort(String portText, String scheme) {
        try {
            return Integer.parseInt(portText.trim());
        } catch (Exception e) {
            return defaultPort(scheme);
        }
    }

    /**
     * 按 scheme 返回默认端口（http=80，https=443）
     */
    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /**
     * 从 Host 头原值中提取纯主机名（去除端口，IPv6 形如 [::1]:9661 已含中括号包裹）
     *
     * @param host Host 头原值
     * @return 纯主机名，无法提取时返回 null
     */
    private String extractHostname(String host) {
        if (!StringUtils.hasText(host)) {
            return null;
        }
        host = host.trim();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : null;
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    /**
     * 判断单个 IP 字面量是否为本机地址
     * <p>仅接受 IP 字面量解析（含冒号按 IPv6 字面量、纯数字点分按 IPv4 字面量），
     * 其余内容（域名、非法字符串等）无法安全判定，一律按可疑来源处理——
     * 同时避免对客户端可控的请求头内容发起 DNS 解析</p>
     * <p>放行范围：回环（127.0.0.1 / ::1）以及本机网卡上的全部地址。
     * 刻意不放行通配地址（0.0.0.0 / ::）：它代表「未指定地址」，出现在 Host 或 XFF 中不构成
     * 「来源为本机」的有效证据，放行会让外部请求凭空获得本机身份</p>
     *
     * @param ipStr 待判定的 IP 字符串
     * @return true 表示为本机地址
     */
    private boolean isLocalAddress(String ipStr) {
        InetAddress addr = parseIpLiteral(ipStr);
        if (addr == null) {
            return false;
        }
        // 本机回环地址
        if (addr.isLoopbackAddress()) {
            return true;
        }
        // 通配地址（0.0.0.0 / ::）不是可信来源标识，直接拒绝
        if (addr.isAnyLocalAddress()) {
            return false;
        }
        // 与本机网卡地址集合精确比对（覆盖本机的局域网 IPv4、IPv6 等地址）
        return getLocalAddresses().contains(addr);
    }

    /**
     * 将字符串严格按 IP 字面量解析为 InetAddress，不发起 DNS 解析
     *
     * @param ipStr 待解析字符串
     * @return 解析成功返回地址对象，非 IP 字面量或解析失败返回 null
     */
    private InetAddress parseIpLiteral(String ipStr) {
        if (!StringUtils.hasText(ipStr)) {
            return null;
        }
        String ip = ipStr.trim();
        // 非 IP 字面量格式（如域名）直接拒绝，防止触发 DNS 解析
        boolean maybeIpLiteral = ip.contains(":") || ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!maybeIpLiteral) {
            return null;
        }
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            log.warn("解析 IP 字面量失败：{}", ip, e);
            return null;
        }
    }

    /**
     * 惰性加载并缓存本机全部网卡地址集合（网卡地址在运行期基本静态，缓存一次即可）
     *
     * @return 本机地址集合
     */
    private Set<InetAddress> getLocalAddresses() {
        Set<InetAddress> cached = localAddresses;
        if (cached != null) {
            return cached;
        }
        Set<InetAddress> addresses = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    addresses.add(inetAddresses.nextElement());
                }
            }
        } catch (Exception e) {
            // 加载失败时保留空集合兜底：仅回环/通配判定生效，按更严格策略执行
            log.warn("加载本机网卡地址集合失败，退化为仅识别回环地址", e);
        }
        cached = Collections.unmodifiableSet(addresses);
        localAddresses = cached;
        return cached;
    }
}
