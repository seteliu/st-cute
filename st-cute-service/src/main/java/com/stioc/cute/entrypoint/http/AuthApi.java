package com.stioc.cute.entrypoint.http;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.common.UserInfo;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.util.UserUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 身份认证与用户信息控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthApi {

    @Resource
    private ContractProperty contractProperty;

    /**
     * 用户登录接口，校验配置的安全密码
     *
     * @param body    包含密码的请求体
     * @param request HTTP 请求对象，用于获取/创建 Session
     * @return 登录结果
     */
    @PostMapping("/login")
    public Result<UserInfo> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String requiredPassword = contractProperty.getPassword();
        if (StringUtils.hasText(requiredPassword)) {
            if (!requiredPassword.equals(body.getPassword())) {
                return Result.error(401, "密码错误，登录失败");
            }
        }

        // 创建或恢复会话
        HttpSession session = request.getSession(true);
        UserInfo userInfo = new UserInfo("admin", "admin");
        session.setAttribute("user", userInfo);

        log.info("用户登录成功，Session ID: {}, 用户: {}", session.getId(), userInfo.getUsername());
        return Result.success(userInfo);
    }

    /**
     * 获取当前线程/会话的用户信息
     *
     * @return 用户信息实体
     */
    @GetMapping("/info")
    public Result<UserInfo> getUserInfo() {
        UserInfo user = UserUtils.getUser();
        if (user == null) {
            return Result.error(401, "未登录或登录已失效");
        }
        return Result.success(user);
    }

    /**
     * 登录请求体对象
     */
    @Data
    public static class LoginRequest {
        private String password;
    }
}
