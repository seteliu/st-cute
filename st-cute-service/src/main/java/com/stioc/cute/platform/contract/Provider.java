package com.stioc.cute.platform.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大模型供应商配置实体。
 * <p>
 * 每条 Provider 记录对应一个供应商下的一个具体模型配置，包含连接协议、API 密钥、
 * 模型参数（温度、上下文窗口、最大迭代轮数）等。配置数据持久化于全局 JSON 文件中，
 * 由 {@link com.stioc.cute.provider.ProviderService} 统一管理生命周期。
 * </p>
 *
 * @see com.stioc.cute.provider.ProviderService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Provider {

    /**
     * 供应商分组名称，用于逻辑归类同一供应商下的多个模型配置。
     * 例如 "OpenAI"、"Anthropic"、"DeepSeek" 等，前端按此字段分组展示。
     */
    private String group;

    /**
     * 大模型交互协议类型。
     * 当前支持：{@code OPENAI}（兼容 OpenAI API 格式，含 DeepSeek 等）、{@code ANTHROPIC}。
     */
    private String protocol;

    /**
     * 大模型 API 的基础请求地址。
     * 若为空，{@code ProviderService} 将根据 protocol 自动选用默认地址
     * （OpenAI: {@code https://api.openai.com/v1}，Anthropic: {@code https://api.anthropic.com/v1}）。
     */
    private String baseUrl;

    /**
     * 是否直接使用完整请求地址（不自动拼接 /chat/completions 或 /messages 等 API 路径）。
     * {@code null} 或 {@code false} 表示默认自动拼接。
     */
    private Boolean useFullUrl;

    /**
     * API 访问密钥。
     * 优先级：显式配置值 → 环境变量 {@code OPENAI_API_KEY} / {@code ANTHROPIC_API_KEY} → 占位符。
     */
    private String apiKey;

    /**
     * 具体模型名称，如 {@code gpt-4o}、{@code claude-sonnet-4-20250514}。
     * 同一 group 下 modelName 必须唯一。
     */
    private String modelName;

    /**
     * 模型推理温度参数（0.0 ~ 2.0），控制输出随机性。
     * 越低越确定、越高越有创造性。
     * {@code null} 表示不传递：Anthropic 侧不传（兼容 extended thinking 等不支持 temperature 的模式），
     * OpenAI 侧内部兜底使用 0.7。
     */
    private Double temperature;

    /**
     * 模型上下文窗口大小（token 数）。
     * <p>
     * 同时作为 Anthropic {@code max_tokens}（必传）和 OpenAI {@code max_tokens}（可选）的传入值。
     * {@code null} 或 0 时：OpenAI 不传该参数（使用模型默认值）；Anthropic 兜底使用 16000。
     * </p>
     * <p>
     * {@link com.stioc.cute.agent.LlmWindowManager} 用此值作为摘要触发（70%）
     * 和防爆裁剪（95%）的窗口上限，未配置时兜底 100K。
     * </p>
     */
    private Integer contextSize;

    /**
     * 单次回答的最大 token 数。
     */
    private Integer maxTokens;

    /**
     * 思考级别（例如: low, medium, high）。
     */
    private String reasoningEffort;

    /**
     * 是否支持多模态（图片/文件附件等）。
     * 默认 false。
     */
    @Builder.Default
    private Boolean multimodal = false;

}

