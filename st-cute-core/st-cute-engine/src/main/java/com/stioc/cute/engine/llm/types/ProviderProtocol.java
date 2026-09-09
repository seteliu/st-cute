package com.stioc.cute.engine.llm.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * 大模型交互协议枚举
 */
@Getter
@RequiredArgsConstructor
public enum ProviderProtocol {

    /**
     * OpenAI Chat Completions 协议（兼容 DeepSeek、通义千问、Kimi 等）
     */
    OPENAI("/chat/completions"),

    /**
     * OpenAI Responses 协议
     */
    OPENAI_RESPONSE("/responses"),

    /**
     * Anthropic Messages 协议
     */
    ANTHROPIC("/messages");

    /**
     * 请求路径后缀（自动拼接在 baseUrl 之后）
     */
    private final String pathSuffix;

    /**
     * 根据协议名称（忽略大小写和首尾空格）安全解析枚举，匹配不到返回 null
     *
     * @param name 协议名称字符串
     * @return 匹配的 ProviderProtocol 枚举，未匹配返回 null
     */
    public static ProviderProtocol fromName(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        for (ProviderProtocol p : values()) {
            if (p.name().equalsIgnoreCase(name.trim())) {
                return p;
            }
        }
        return null;
    }
}
