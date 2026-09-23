package com.stioc.cute.controller;

import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.contract.SecurityProperty;
import com.stioc.cute.platform.security.DesktopTokenStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 切片测试的安全过滤器属性装配。
 * <p>
 * 提供过滤器链与 {@link com.stioc.cute.controller.ConfigController} 依赖的真实属性 bean：
 * <ul>
 *   <li>{@code ContractProperty}：真实实例（password 默认 null，即免密模式）。
 *       注意其 {@code @PostConstruct} 会尝试读取测试隔离目录下的 config.json 合并，
 *       故涉及鉴权行为的用例必须显式钉住模式（见基类的 {@code ensurePasswordlessMode}），
 *       不得依赖"默认就是免密"的环境假设</li>
 *   <li>{@code SecurityProperty}：真实实例（trustedOrigins 默认空）</li>
 *   <li>{@code DesktopTokenStore}：真实实例（非桌面壳托管模式下凭证机制天然不激活，
 *       不落任何文件、不起守护线程）</li>
 * </ul>
 * {@code ContractProperty} 用真实实例而非 Mockito mock 的原因：过滤器与配置控制器
 * 需要真正执行 {@code getPassword()} 判断逻辑，mock 一个 @Data POJO 无任何收益；
 * 用例需要密码模式时在测试内 setter 翻转状态即可（@AfterEach 由基类统一复位）。
 * </p>
 */
@Configuration
public class SlicePropertyConfig {

    @Bean
    ContractProperty contractProperty() {
        return new ContractProperty();
    }

    @Bean
    SecurityProperty securityProperty() {
        return new SecurityProperty();
    }

    @Bean
    DesktopTokenStore desktopTokenStore() {
        return new DesktopTokenStore();
    }
}
