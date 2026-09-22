package com.stioc.cute.platform.security;

import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.util.PasswordDigestKit;
import com.stioc.cute.platform.util.PasswordPolicy;
import com.stioc.cute.provider.ProviderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 访问码启动迁移：把 config.json 中手写的明文访问码升级为带盐摘要存储。
 * <p>
 * 存在意义：容器化 / 无界面的部署形态下，用户无法进入设置页面配置访问码，
 * 只能在 config.json 中直接写入明文（后端识别为「历史明文形态」即可正常登录）；
 * 该形态一旦被识别，即在本组件中完成一次性升级，使明文不在磁盘上长期留存。
 * </p>
 * <p>
 * 执行时机：{@link ApplicationReadyEvent} 之后（此时 {@link ContractProperty} 已完成
 * config.json 合并、{@link ProviderService} 已就绪），避免在配置类的
 * {@code @PostConstruct} 阶段反向依赖宿主服务形成初始化环。
 * </p>
 * <p>
 * 策略校验：本阶段是后端唯一能读到访问码原文的时机，故在此做完整的
 * {@link PasswordPolicy} 校验（长度区间、字母与数字组成、字符白名单）。
 * 不合规时<b>刻意保留原值与磁盘内容不变</b>并打 ERROR 日志：
 * 静默清除会让用户误以为「密码已生效」，而保留原值至少让用户在 config.json 中
 * 看得见、可手工修正；登录时另有策略同检兜底拦截（见 LoginService）。
 * </p>
 */
@Slf4j
@Component
public class StartupPasswordMigrator {

    @Resource
    private ContractProperty contractProperty;

    @Resource
    private ProviderService providerService;

    /**
     * 应用就绪后执行一次明文访问码迁移
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migratePlaintextPassword() {
        String storedPassword = contractProperty.getPassword();
        // 未配置访问码：无需处理
        if (!StringUtils.hasText(storedPassword)) {
            return;
        }
        // 摘要形态：已为最终形态，无需迁移
        if (PasswordDigestKit.isDigested(storedPassword)) {
            return;
        }
        // 明文形态：先按安全策略校验，不合规则保留原值并明确报错（不静默清除）
        if (!PasswordPolicy.matches(storedPassword)) {
            log.error("检测到 config.json 中的安全访问码不符合安全策略，已保留原值未做迁移，请手工修正后重启：{}", PasswordPolicy.describe());
            return;
        }
        try {
            // 升级口径与登录链路的透明升级保持一致：存储 hash(SHA-256hex(原文))
            providerService.saveSettingsKeepPasswordDigest(PasswordDigestKit.hash(PasswordDigestKit.sha256Hex(storedPassword)));
            log.info("检测到 config.json 中的明文安全访问码，已自动升级为带盐摘要存储");
        } catch (Exception e) {
            // 写盘失败不阻断启动：本次保留明文，下次启动重试（明文形态仍可正常登录）
            log.error("明文安全访问码升级摘要存储失败，本次保留明文形态，将在下次启动重试", e);
        }
    }
}
