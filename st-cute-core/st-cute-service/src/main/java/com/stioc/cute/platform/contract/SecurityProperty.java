package com.stioc.cute.platform.contract;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Web 安全过滤配置属性（部署级静态配置）。
 * <p>
 * 独立于 {@link ContractProperty} 存在：故意不挂接 st-cute 根前缀配置类，
 * 因此不参与用户全局 config.json（系统设置）的深度合并，
 * 仅认 application.yml 部署级静态配置（与 {@link SearxngProperty} 同款定位）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "st-cute.security")
public class SecurityProperty {

    /**
     * 跨源来源白名单：同源校验之外额外放行的来源，供部署方按需追加。
     * <p>
     * 常态下无需配置任何条目，同源校验已自适应域名、IP、端口与协议（含反代场景）；
     * 仅当存在「请求 Origin 与 Host 天然不一致且确属可信」的部署形态时才需填写。
     * 条目支持「主机名」或「主机名:端口」两种写法，主机名比较忽略大小写。
     * </p>
     * <p>
     * 注意：桌面壳固定来源 tauri.localhost 由过滤器内置放行，无需在此重复声明。
     * </p>
     */
    private List<String> trustedOrigins = new ArrayList<>();
}
