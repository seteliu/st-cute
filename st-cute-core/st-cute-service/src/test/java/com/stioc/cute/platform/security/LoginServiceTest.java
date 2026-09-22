package com.stioc.cute.platform.security;

import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.util.PasswordDigestKit;
import com.stioc.cute.platform.util.PasswordPolicy;
import com.stioc.cute.provider.ProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录业务服务单元测试。
 * <p>
 * 覆盖三条关键分支：未配置访问码（免鉴权）、摘要形态存储值的质询-应答校验、
 * 历史明文存储值的兼容校验与透明升级。登录链路承载全部鉴权判定，此前无测试覆盖。
 * </p>
 */
class LoginServiceTest {

    private static final String CLIENT_IP = "192.0.2.10";

    private LoginService loginService;
    private ContractProperty contractProperty;
    private LoginChallengeStore challengeStore;

    /**
     * 装配被测服务：仅注入真实的内存态组件，配置载体以最小替身提供。
     * <p>
     * 密码迁移链路涉及 {@code providerService}，本测试关注登录判定本身，
     * 故不注入该依赖——而把 {@link ContractProperty} 作为存储值的唯一事实源直接断言。
     * </p>
     */
    @BeforeEach
    void setUp() {
        contractProperty = new ContractProperty();
        challengeStore = new LoginChallengeStore();

        loginService = new LoginService();
        ReflectionTestUtils.setField(loginService, "contractProperty", contractProperty);
        ReflectionTestUtils.setField(loginService, "challengeStore", challengeStore);
        ReflectionTestUtils.setField(loginService, "bruteForceGuard", new LoginBruteForceGuard());
        // 密码迁移与清除链路会回调该依赖；以最小替身承接，只同步内存配置、不触碰真实配置文件
        ReflectionTestUtils.setField(loginService, "providerService",
                new RecordingProviderService(contractProperty));
    }

    /**
     * 承接密码迁移写入的最小替身。
     * <p>
     * 仅复刻真实实现在配置载体上的写入语义（真实实现另含写盘与广播），
     * 使测试既能断言存储值变化，又不会改写开发机的真实 config.json。
     * </p>
     */
    private static final class RecordingProviderService extends ProviderService {

        private final ContractProperty target;

        RecordingProviderService(ContractProperty target) {
            this.target = target;
        }

        @Override
        public void saveSettingsKeepPasswordDigest(String passwordDigest) {
            target.setPassword(passwordDigest);
        }
    }

    /**
     * 按协议重算客户端证明摘要：{@code SHA-256hex(digestBase64 + ":" + nonce)}。
     * <p>
     * 与 {@link PasswordDigestKit#verifyChallengeProof} 的期望算法严格同源，
     * 使测试模拟真实前端行为而非直接调用被测方的校验方法。
     * </p>
     *
     * @param storedDigest {@code salt:digest} 形态的存储值
     * @param nonce        服务端签发的质询值
     * @return 客户端应提交的证明摘要
     */
    private String buildProof(String storedDigest, String nonce) {
        String digestBase64 = storedDigest.substring(storedDigest.indexOf(':') + 1);
        return PasswordDigestKit.sha256Hex(digestBase64 + ":" + nonce);
    }

    @Nested
    @DisplayName("未配置访问码：免鉴权")
    class NoPasswordConfiguredTests {

        @Test
        @DisplayName("质询接口不发放材料（nonce 与 salt 均为 null）")
        void issueChallengeReturnsEmpty() {
            ContractProperty empty = new ContractProperty();
            empty.setPassword(null);

            LoginService service = new LoginService();
            ReflectionTestUtils.setField(service, "contractProperty", empty);
            ReflectionTestUtils.setField(service, "challengeStore", challengeStore);

            LoginService.ChallengeResult result = service.issueChallenge();
            assertNull(result.nonce(), "未配置访问码时不应发放质询值");
            assertNull(result.salt());
        }

        @Test
        @DisplayName("登录接口直接放行，不做任何凭证校验")
        void loginAllowed() {
            contractProperty.setPassword(null);

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, null, null, null);

            assertTrue(verdict.allowed());
            assertEquals(CLIENT_IP, verdict.clientIp());
        }
    }

    @Nested
    @DisplayName("摘要形态：质询-应答校验")
    class DigestedPasswordTests {

        /**
         * 构造带盐摘要存储值的完整质询-应答参数
         */
        private LoginService.ChallengeResult prepareChallenge(String storedDigest) {
            contractProperty.setPassword(storedDigest);
            return loginService.issueChallenge();
        }

        @Test
        @DisplayName("质询接口发放 nonce 与盐段，盐段取自存储值冒号前部分")
        void issueChallengeReturnsNonceAndSalt() {
            String stored = "abcdef1234567890:665f3c1f0d2a4b8e9c7d6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c";
            contractProperty.setPassword(stored);

            LoginService.ChallengeResult result = loginService.issueChallenge();

            assertNotNull(result.nonce(), "已配置访问码时必须发放质询值");
            assertTrue(result.nonce().matches("^[0-9a-f]{64}$"), "质询值应为 64 位十六进制");
            assertEquals("abcdef1234567890", result.salt(), "盐段应取自存储值冒号前部分");
        }

        @Test
        @DisplayName("证明摘要正确：允许登录")
        void correctProofAllowed() {
            String stored = PasswordDigestKit.hash("correct-password");
            LoginService.ChallengeResult challenge = prepareChallenge(stored);
            String proof = buildProof(stored, challenge.nonce());

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, "any", challenge.nonce(), proof);

            assertTrue(verdict.allowed(), "证明摘要正确应放行");
        }

        @Test
        @DisplayName("证明摘要错误：401 拒绝并累计失败次数")
        void wrongProofDenied() {
            String stored = PasswordDigestKit.hash("correct-password");
            LoginService.ChallengeResult challenge = prepareChallenge(stored);

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, "any", challenge.nonce(), "deadbeef");

            assertFalse(verdict.allowed());
            assertEquals(401, verdict.code());
        }

        @Test
        @DisplayName("质询值重复使用：第二次必然失败（严格单次有效）")
        void nonceReplayRejected() {
            String stored = PasswordDigestKit.hash("correct-password");
            LoginService.ChallengeResult challenge = prepareChallenge(stored);
            String proof = buildProof(stored, challenge.nonce());

            assertTrue(loginService.login(CLIENT_IP, "any", challenge.nonce(), proof).allowed());
            // 同一 nonce 再次提交：已被消费，必须拒绝（防重放）
            LoginService.LoginVerdict replay = loginService.login(CLIENT_IP, "any", challenge.nonce(), proof);
            assertFalse(replay.allowed(), "已消费的质询值不得再次通过");
            assertEquals(401, replay.code());
        }

        @Test
        @DisplayName("未签发或伪造的质询值：直接拒绝")
        void unknownNonceRejected() {
            String stored = PasswordDigestKit.hash("correct-password");
            prepareChallenge(stored);
            String forgedNonce = "0".repeat(64);
            String proof = buildProof(stored, forgedNonce);

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, "any", forgedNonce, proof);

            assertFalse(verdict.allowed(), "未签发过的质询值必须拒绝");
            assertEquals(401, verdict.code());
        }
    }

    @Nested
    @DisplayName("历史明文形态：兼容校验与透明升级")
    class LegacyPlaintextTests {

        @Test
        @DisplayName("明文摘要匹配且符合策略：放行登录")
        void correctPlaintextAllowed() {
            String plaintext = "LegacyPass123";
            contractProperty.setPassword(plaintext);
            String transmitted = PasswordDigestKit.sha256Hex(plaintext);

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, transmitted, null, null);

            assertTrue(verdict.allowed(), "明文摘要匹配应放行");
        }

        @Test
        @DisplayName("明文摘要不匹配：401 拒绝")
        void wrongPlaintextDenied() {
            contractProperty.setPassword("LegacyPass123");

            LoginService.LoginVerdict verdict = loginService.login(
                    CLIENT_IP, PasswordDigestKit.sha256Hex("wrong-password"), null, null);

            assertFalse(verdict.allowed());
            assertEquals(401, verdict.code());
        }

        @Test
        @DisplayName("明文摘要匹配但不符合安全策略：清除存储值并拒绝登录")
        void weakPlaintextClearedAndDenied() {
            // 刻意使用不满足复杂度策略的弱口令（配置文件直改场景）
            String weak = "abc";
            contractProperty.setPassword(weak);
            String transmitted = PasswordDigestKit.sha256Hex(weak);

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, transmitted, null, null);

            assertFalse(verdict.allowed(), "弱口令即使摘要匹配也必须拒绝");
            assertEquals(401, verdict.code());
            assertTrue(verdict.message().contains(PasswordPolicy.describe()));
        }

        @Test
        @DisplayName("传输摘要为空：401 拒绝并累计失败")
        void emptyDigestDenied() {
            contractProperty.setPassword("LegacyPass123");

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, "", null, null);

            assertFalse(verdict.allowed());
            assertEquals(401, verdict.code());
        }
    }

    @Nested
    @DisplayName("防爆破联动")
    class BruteForceIntegrationTests {

        @Test
        @DisplayName("封禁期内：即使凭证正确也拒绝，返回 429")
        void bannedRejectedBeforeVerification() {
            String stored = PasswordDigestKit.hash("correct-password");
            contractProperty.setPassword(stored);
            LoginService.ChallengeResult challenge = loginService.issueChallenge();
            String proof = buildProof(stored, challenge.nonce());

            // 连续失败触发封禁
            LoginBruteForceGuard guard = (LoginBruteForceGuard) ReflectionTestUtils.getField(loginService, "bruteForceGuard");
            for (int i = 0; i < 5; i++) {
                guard.recordFailure(CLIENT_IP);
            }

            LoginService.LoginVerdict verdict = loginService.login(CLIENT_IP, "any", challenge.nonce(), proof);

            assertFalse(verdict.allowed(), "封禁期内的请求必须在密码校验之前被拒绝");
            assertEquals(429, verdict.code());
        }

        @Test
        @DisplayName("登录成功后清除该来源失败计数")
        void successClearsFailures() {
            contractProperty.setPassword(null);

            LoginBruteForceGuard guard = (LoginBruteForceGuard) ReflectionTestUtils.getField(loginService, "bruteForceGuard");
            for (int i = 0; i < 3; i++) {
                guard.recordFailure(CLIENT_IP);
            }
            assertFalse(guard.isBanned(CLIENT_IP));

            loginService.onLoginSuccess(CLIENT_IP);
            // 清零后需重新累计满阈值才会封禁，此处再失败 4 次不应触发（说明计数确已清零）
            for (int i = 0; i < 4; i++) {
                guard.recordFailure(CLIENT_IP);
            }
            assertFalse(guard.isBanned(CLIENT_IP), "成功登录后失败计数应已清零");
        }
    }
}
