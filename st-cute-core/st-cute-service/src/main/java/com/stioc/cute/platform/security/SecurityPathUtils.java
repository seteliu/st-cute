package com.stioc.cute.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 安全过滤器的请求路径处理工具。
 * <p>
 * 集中承载「取真实路径」与「路径规范化」两件事，供 {@link WebSecurityFilter} 与
 * {@link DesktopSecurityFilter} 共用，保证两个过滤器对同一请求的路径判定口径完全一致。
 * </p>
 * <p>
 * 设计要点：
 * <ol>
 *     <li>取值必须使用容器解码后的 {@code getServletPath()} 而非原始 {@code getRequestURI}：
 *     原始 URI 未经 URL 解码（如 {@code /api/%73hutdown}）会导致过滤器判定与 Spring 实际路由
 *     不一致，攻击者可借此形变路径绕过校验直达控制器，构成 fail-open 漏洞</li>
 *     <li>不同容器在解码与规范化时机上存在差异，故过滤器侧再自行兜底归一，
 *     确保与 Spring 路由视角的路径判定一致</li>
 * </ol>
 * </p>
 */
public final class SecurityPathUtils {

    private SecurityPathUtils() {
        // 工具类禁止实例化
    }

    /**
     * 解析请求的安全判定路径：优先取容器解码后的 servletPath，异常或为空时退回 requestURI 并做 URL 解码
     * <p>
     * 退回分支刻意不再直接使用原始 requestURI：该值在部分容器下保持编码形态，
     * 若直接参与匹配会形成「过滤器判不中、路由却命中」的形变绕过窗口
     * </p>
     *
     * @param request 当前 HTTP 请求
     * @return 规范化后的路径；无法取得时返回 null
     */
    public static String resolvePath(HttpServletRequest request) {
        // 判空必须基于原始 servletPath：normalizePath("") 会返回 "/"，若以归一化结果判空，
        // 空 servletPath 会被误判为「根路径」而跳过 URI 兜底，使路径判定退化为 "/" 并绕过后续所有前缀匹配
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.trim().isEmpty()) {
            return normalizePath(servletPath);
        }
        // 兜底分支：servletPath 为空时退回原始 URI，先做一次百分号解码再归一，杜绝编码形态绕过
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        return normalizePath(urlDecode(uri));
    }

    /**
     * 规范化请求路径用于安全匹配
     * <p>统一处置各类路径形变：反斜杠归一为斜杠、剔除分号后的路径参数（如 ;jsessionid）、
     * 折叠多级斜杠、解析 "." 与 ".." 相对段。Tomcat 等容器在解析期已有类似规范化，
     * 但不同容器实现存在差异，过滤器侧自行兜底归一，确保与 Spring 路由视角的
     * 路径判定完全一致，杜绝形变路径绕过</p>
     *
     * @param raw 原始路径（servletPath 已由容器完成 URL 解码）
     * @return 以 / 开头的规范化路径；入参为 null 时返回 null
     */
    public static String normalizePath(String raw) {
        if (raw == null) {
            return null;
        }
        // Windows 语义下反斜杠等价于路径分隔符，统一归一防止混用形变
        String unified = raw.replace('\\', '/');
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : unified.split("/")) {
            // 剔除分号后的路径参数，防止参数注入形变
            int semicolon = segment.indexOf(';');
            if (semicolon >= 0) {
                segment = segment.substring(0, semicolon);
            }
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                // 越出根目录时直接丢弃该段，保持 fail-close 语义
                segments.pollLast();
                continue;
            }
            segments.addLast(segment);
        }
        return "/" + String.join("/", segments);
    }

    /**
     * 对路径做一次百分号解码（UTF-8），解码失败时原样返回
     * <p>
     * 仅用于 servletPath 为空的兜底分支：此时拿到的原始 URI 可能仍带编码，
     * 解码后参与归一才能与 Spring 路由口径一致
     * </p>
     *
     * @param raw 原始路径
     * @return 解码后的路径；无法解码时返回原值
     */
    private static String urlDecode(String raw) {
        if (raw == null || raw.indexOf('%') < 0) {
            return raw;
        }
        try {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 畸形百分号编码无法解码：保持原值，由后续归一与匹配逻辑按 fail-close 处理
            return raw;
        }
    }
}
