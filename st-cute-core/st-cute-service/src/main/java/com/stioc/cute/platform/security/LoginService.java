package com.stioc.cute.platform.security;

import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.util.PasswordDigestKit;
import com.stioc.cute.platform.util.PasswordPolicy;
import com.stioc.cute.provider.ProviderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 登录业务服务：承载登录校验的全部业务逻辑，控制器仅做协议编排与结果映射。
 * <p>
 * 协议说明（摘要形态存储值）采用质询-应答模式：客户端先经质询接口取得服务端签发的一次性
 * nonce 与盐，以"存储摘要"为密钥材料在本地计算证明摘要后随质询值一并提交；
 * 服务端消费质询值并用自身存储值重算证明比对，freshness 材料在密码学上绑定进证明值。
 * </p>
 * <p>
 * 安全策略：接入防爆破守卫（滑动窗口失败计数 + 封禁）；历史明文存储值登录成功后透明升级为
 * 带盐摘要，并做访问码安全策略同检，不合规则清除，防止配置文件直改弱密码绕过前端校验。
 * </p>
 */
@Slf4j
@Service
public class LoginService {

    @Resource
    private ContractProperty contractProperty;
    @Resource
    private ProviderService providerService;
    @Resource
    private LoginBruteForceGuard bruteForceGuard;
    @Resource
    private LoginChallengeStore challengeStore;

    /**
     * 登录质询结果（nonce 与存储值盐段）
     *
     * @param nonce 一次性质询值；null 表示当前未配置安全访问码、无需鉴权
     * @param salt  存储值的盐段（客户端重算存储摘要所必需）
     */
    public record ChallengeResult(String nonce, String salt) {
    }

    /**
     * 登录校验结果
     *
     * @param allowed 是否允许登录
     * @param code    拒绝时的业务状态码（429 封禁 / 401 校验失败）
     * @param message 拒绝时的提示文案
     * @param clientIp 来源 IP（TCP 对端地址，供调用方在登录成功后清理失败计数）
     */
    public record LoginVerdict(boolean allowed, int code, String message, String clientIp) {

        /**
         * 构造允许登录的裁决
         *
         * @param clientIp 来源 IP
         * @return 允许登录的裁决
         */
        public static LoginVerdict allow(String clientIp) {
            return new LoginVerdict(true, 0, null, clientIp);
        }

        /**
         * 构造拒绝登录的裁决
         *
         * @param code     业务状态码
         * @param message  提示文案
         * @param clientIp 来源 IP
         * @return 拒绝登录的裁决
         */
        public static LoginVerdict deny(int code, String message, String clientIp) {
            return new LoginVerdict(false, code, message, clientIp);
        }
    }

    /**
     * 签发登录质询材料
     * <p>仅在已配置安全访问码时发放；未配置时返回 nonce 为 null 的结果，
     * 前端据此跳过质询流程（登录接口同样不做校验）。</p>
     *
     * @return 质询材料（nonce 与盐段）
     */
    public ChallengeResult issueChallenge() {
        String storedPassword = contractProperty.getPassword();
        if (!StringUtils.hasText(storedPassword)) {
            // 未配置访问码：无鉴权需求，不发放质询材料
            return new ChallengeResult(null, null);
        }
        // 存储形态为 salt:digest，取盐段供客户端重算存储摘要；历史明文形态无盐段，返回空串
        int idx = storedPassword.indexOf(':');
        String salt = idx > 0 ? storedPassword.substring(0, idx) : "";
        return new ChallengeResult(challengeStore.issue(), salt);
    }

    /**
     * 执行登录校验
     *
     * @param clientIp          来源 IP（TCP 对端地址，登录前 X-Forwarded-For 可被伪造，不可作为计数依据）
     * @param transmittedDigest 前端提交的 SHA-256(原文) 十六进制传输摘要
     * @param nonce             质询接口取得的一次性质询值（摘要形态存储值必传）
     * @param proof             证明摘要（摘要形态存储值必传）
     * @return 登录裁决结果
     */
    public LoginVerdict login(String clientIp, String transmittedDigest, String nonce, String proof) {
        // 防爆破前置检查：处于封禁期直接拒绝，不进入任何密码校验逻辑
        if (bruteForceGuard.isBanned(clientIp)) {
            log.warn("来源 IP = {} 处于登录防爆破封禁期，已拒绝登录请求", clientIp);
            return LoginVerdict.deny(429, "尝试次数过多，请稍后再试", clientIp);
        }

        String storedPassword = contractProperty.getPassword();
        // 未配置访问码：直接放行（安全边界由 WebSecurityFilter 的本机来源限制兜底）
        if (!StringUtils.hasText(storedPassword)) {
            return LoginVerdict.allow(clientIp);
        }

        if (!StringUtils.hasText(transmittedDigest)) {
            bruteForceGuard.recordFailure(clientIp);
            return LoginVerdict.deny(401, "密码错误，登录失败", clientIp);
        }

        if (PasswordDigestKit.isDigested(storedPassword)) {
            return verifyDigested(clientIp, storedPassword, nonce, proof);
        }
        return verifyLegacyPlaintext(clientIp, storedPassword, transmittedDigest);
    }

    /**
     * 摘要形态存储值的质询-应答校验
     * <p>质询值须真实签发、在有效期内且未被消费过（消费为原子移除，严格单次有效）；
     * 证明摘要由"存储摘要段 + 质询值"重算比对，freshness 在密码学上绑定进证明值。</p>
     */
    private LoginVerdict verifyDigested(String clientIp, String storedPassword, String nonce, String proof) {
        // 先消费质询值：无论后续校验成败均不再可用，杜绝同一质询被反复尝试
        if (!challengeStore.consume(nonce)) {
            return LoginVerdict.deny(401, "登录质询已失效，请刷新后重试", clientIp);
        }
        if (!PasswordDigestKit.verifyChallengeProof(storedPassword, nonce, proof)) {
            bruteForceGuard.recordFailure(clientIp);
            return LoginVerdict.deny(401, "密码错误，登录失败", clientIp);
        }
        return LoginVerdict.allow(clientIp);
    }

    /**
     * 历史明文存储值的兼容校验
     * <p>按前端同款算法对明文做一次摘要后比对；校验通过后透明升级为带盐摘要存储，
     * 并做访问码安全策略同检（不合规则清除存储值并拒绝登录，防止配置文件直改弱密码）。
     * 该形态无盐段可用，不参与质询-应答协议，升级完成后下次登录即切换。</p>
     */
    private LoginVerdict verifyLegacyPlaintext(String clientIp, String storedPassword, String transmittedDigest) {
        // 恒定时间比较：与实际摘要比对口径统一，避免逐字符短路返回造成的时序侧信道
        if (!MessageDigest.isEqual(
                PasswordDigestKit.sha256Hex(storedPassword).getBytes(StandardCharsets.UTF_8),
                transmittedDigest.getBytes(StandardCharsets.UTF_8))) {
            bruteForceGuard.recordFailure(clientIp);
            return LoginVerdict.deny(401, "密码错误，登录失败", clientIp);
        }
        // 存储值校验通过后做安全策略同检：不合规（如直接在 config.json 改出弱密码）即清除并拒绝
        if (!PasswordPolicy.matches(storedPassword)) {
            log.warn("存储的历史明文访问码不符合安全策略，已清除，请重新配置：{}", PasswordPolicy.describe());
            providerService.saveSettingsKeepPasswordDigest(null);
            bruteForceGuard.recordFailure(clientIp);
            return LoginVerdict.deny(401, "安全访问码不符合安全策略（" + PasswordPolicy.describe() + "），请重新设置", clientIp);
        }
        try {
            String upgraded = PasswordDigestKit.hash(transmittedDigest);
            providerService.saveSettingsKeepPasswordDigest(upgraded);
            log.info("检测到历史明文密码，已透明升级为带盐摘要存储");
            return LoginVerdict.allow(clientIp);
        } catch (Exception e) {
            // 迁移失败不阻断登录，下次登录再试
            log.warn("历史明文密码升级摘要存储失败，将在下次登录重试", e);
            return LoginVerdict.allow(clientIp);
        }
    }

    /**
     * 记录登录成功：清除该来源的全部失败计数
     *
     * @param clientIp 来源 IP（TCP 对端地址）
     */
    public void onLoginSuccess(String clientIp) {
        bruteForceGuard.recordSuccess(clientIp);
    }
}
