package com.stioc.cute.platform.contract;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SearXNG 联网搜索服务配置属性。
 * <p>
 * 独立于 {@link ContractProperty} 存在：故意不挂接 st-cute 根前缀配置类，
 * 因此不参与用户全局 config.json（系统设置）的深度合并，
 * 仅认 application.yml 部署级静态配置。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "st-cute.search.searxng")
public class SearxngProperty {

    /**
     * 服务基地址（如 http://61jun.com:9201，不含 /search 路径），
     * 未配置时搜索工具整体对模型不可见
     */
    private String baseUrl;

    /**
     * 访问令牌（query 参数 tokens）。
     * 可空：为空时不携带鉴权参数，适配无鉴权的自建实例
     */
    private String token;

    /**
     * 搜索请求读取超时时间（秒），默认 15
     */
    private int timeoutSeconds = 15;
}
