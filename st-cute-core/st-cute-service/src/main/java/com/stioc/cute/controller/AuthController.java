package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.common.UserInfo;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.util.PasswordDigestKit;
import com.stioc.cute.platform.util.UserUtils;
import com.stioc.cute.provider.ProviderService;
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
public class AuthController {

    @Resource
    private ContractProperty contractProperty;
    @Resource
    private ProviderService providerService;

    /**
     * 用户登录接口，校验配置的安全密码。
     * <p>
     * 前端发送的 password 为 SHA-256(原文) 十六进制摘要（传输加密），服务端按存储形态分别处理：
     * 摘要形态存储值直接与传输摘要比对；历史明文存储值先按同算法对明文做一次摘要再比对，
     * 登录成功后透明升级为带盐摘要落盘（由 {@link PasswordDigestKit} 完成迁移写出，见 ConfigController 保存链路）。
     * </p>
     *
     * @param body    包含密码摘要的请求体
     * @param request HTTP 请求对象，用于获取/创建 Session
     * @return 登录结果
     */
    @PostMapping("/login")
    public Result<UserInfo> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String storedPassword = contractProperty.getPassword();
        if (StringUtils.hasText(storedPassword)) {
            String transmittedDigest = body != null ? body.getPassword() : null;
            if (!StringUtils.hasText(transmittedDigest)) {
                return Result.error(401, "密码错误，登录失败");
            }
            boolean matched;
            if (PasswordDigestKit.isDigested(storedPassword)) {
                // 摘要形态：用传输摘要作为"明文"参与带盐摘要校验
                matched = PasswordDigestKit.matches(transmittedDigest, storedPassword);
            } else {
                // 历史明文形态：按前端同款算法对明文做一次摘要后比对，成功则透明升级为带盐摘要
                matched = PasswordDigestKit.sha256Hex(storedPassword).equals(transmittedDigest);
                if (matched) {
                    try {
                        String upgraded = PasswordDigestKit.hash(transmittedDigest);
                        providerService.saveSettingsKeepPasswordDigest(upgraded);
                        log.info("检测到历史明文密码，已透明升级为带盐摘要存储");
                    } catch (Exception e) {
                        // 迁移失败不阻断登录，下次登录再试
                        log.warn("历史明文密码升级摘要存储失败，将在下次登录重试", e);
                    }
                }
            }
            if (!matched) {
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
