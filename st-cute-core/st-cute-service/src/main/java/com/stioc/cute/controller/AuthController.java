package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.common.UserInfo;
import com.stioc.cute.platform.security.LoginService;
import com.stioc.cute.platform.security.types.ChallengeDto;
import com.stioc.cute.platform.security.types.LoginRequest;
import com.stioc.cute.platform.util.UserUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 身份认证与用户信息控制器
 * <p>登录协议与安全校验的全部业务逻辑由 {@link LoginService} 承载，本控制器仅做
 * 请求编排、会话建立与结果映射。</p>
 * <p>登录协议（摘要形态存储值）采用质询-应答模式：客户端先经 {@code GET /api/auth/challenge}
 * 取得服务端签发的一次性质询值，以"存储摘要"为密钥材料在本地计算证明摘要后随质询值一并提交；
 * 服务端消费质询值并用自身存储值重算证明比对，freshness 材料在密码学上绑定进证明值。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Resource
    private LoginService loginService;

    /**
     * 登录质询接口：为客户端签发一次性质询值与证明计算所需的盐。
     * <p>仅在已配置安全访问码时发放（未配置时返回空数据，登录接口同样不校验）；
     * 质询值自签发起 5 分钟内有效且仅可消费一次。客户端基于盐与原文摘要重算存储摘要
     * {@code D = Base64(SHA-256(salt + SHA-256hex(原文)))}，再以 D 与质询值计算证明摘要提交，
     * freshness 由服务端有效期判定，客户端时钟偏移不影响登录。</p>
     *
     * @return 质询材料（未配置访问码时 data 为 null）
     */
    @GetMapping("/challenge")
    public Result<ChallengeDto> challenge() {
        LoginService.ChallengeResult result = loginService.issueChallenge();
        if (result.nonce() == null) {
            return Result.success(null);
        }
        ChallengeDto dto = new ChallengeDto();
        dto.setNonce(result.nonce());
        dto.setSalt(result.salt());
        return Result.success(dto);
    }

    /**
     * 用户登录接口：校验访问码并建立会话。
     * <p>校验裁决（含封禁、质询应答、历史明文迁移与策略同检）均由 {@link LoginService} 给出，
     * 本方法仅负责会话建立与结果映射。</p>
     *
     * @param body    包含密码摘要与质询应答的请求体
     * @param request HTTP 请求对象，用于获取/创建 Session
     * @return 登录结果
     */
    @PostMapping("/login")
    public Result<UserInfo> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        // 计数维度取 TCP 对端地址：登录前 X-Forwarded-For 可被伪造，不可作为依据
        String clientIp = request.getRemoteAddr();
        LoginService.LoginVerdict verdict = loginService.login(clientIp,
                body != null ? body.getPassword() : null,
                body != null ? body.getNonce() : null,
                body != null ? body.getProof() : null);
        if (!verdict.allowed()) {
            return Result.error(verdict.code(), verdict.message());
        }

        // 创建或恢复会话
        HttpSession session = request.getSession(true);
        UserInfo userInfo = new UserInfo("admin", "admin");
        session.setAttribute("user", userInfo);

        // 登录成功：清除该来源的全部失败计数
        loginService.onLoginSuccess(verdict.clientIp());

        log.info("用户登录成功，Session ID: {}, 用户: {}", session.getId(), userInfo.getUsername());
        return Result.success(userInfo);
    }

    /**
     * 用户登出接口：销毁服务端会话并清理本地状态。
     * <p>幂等设计：未登录或会话已失效时同样返回成功，避免前端重复调用产生无意义报错；
     * 会话销毁后其携带的会话 Cookie 立即失效，前端凭据随之作废。</p>
     *
     * @param request HTTP 请求对象，用于取得当前会话
     * @return 固定成功结果
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        // 不允许创建新会话：无会话时直接返回成功
        HttpSession session = request.getSession(false);
        if (session != null) {
            String sessionId = session.getId();
            try {
                session.invalidate();
                log.info("用户已登出，Session 已销毁: {}", sessionId);
            } catch (IllegalStateException e) {
                // 会话在并发场景下可能已被销毁，按幂等成功处理
                log.debug("登出时会话已失效: {}", sessionId);
            }
        }
        return Result.success();
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
}
