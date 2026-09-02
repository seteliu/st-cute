package com.stioc.cute.platform.filter;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.platform.common.UserInfo;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.util.UserUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 访问控制与身份鉴权过滤器
 */
@Component
public class SecurityFilter implements Filter {

    private static final String[] WHITE_LIST = {
        "/api/auth/login",
        "/api/mock/mcp/sse",
        "/api/mock/mcp/message",
        "/api/ping",
        "/api/shutdown"
    };

    private static final String[] INTERCEPT_PREFIXES = {
        "/api",
        "/ws"
    };

    private final ContractProperty contractProperty;

    /**
     * 构造方法注入配置属性
     *
     * @param contractProperty 配置属性
     */
    public SecurityFilter(ContractProperty contractProperty) {
        this.contractProperty = contractProperty;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        try {
            // 1. 尝试解析并注入用户信息到 ThreadLocal
            String requiredPassword = contractProperty.getPassword();
            if (!StringUtils.hasText(requiredPassword)) {
                // 未配置密码：免密登录，直接注入默认管理员用户
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

            // 2. 如果已成功注入用户信息（已登录），则直接放行
            if (UserUtils.getUser() != null) {
                chain.doFilter(request, response);
                return;
            }

            // 3. 未登录状态下，若匹配白名单路由，放行（此时 ThreadLocal 中用户信息为空）
            for (String whiteUrl : WHITE_LIST) {
                if (whiteUrl.equals(path)) {
                    chain.doFilter(request, response);
                    return;
                }
            }

            // 4. 未登录状态下，若不匹配需要拦截的前缀（例如静态网页资源），放行
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

            // 5. 既未登录，又不在白名单，且匹配拦截前缀：返回 401 状态码，并输出 JSON 实体对象
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json;charset=utf-8");

            JSONObject errJson = new JSONObject();
            errJson.put("code", 401);
            errJson.put("msg", "未登录或登录已过期，请重新登录");
            httpResponse.getWriter().write(errJson.toJSONString());

        } finally {
            // 6. 最终彻底清理当前线程的 ThreadLocal 信息，防止内存泄漏和线程复用污染
            UserUtils.clear();
        }
    }
}
