package com.stioc.cute.agent;

import com.stioc.cute.platform.contract.Provider;
import com.stioc.cute.llm.CuteChatOptions;
import com.stioc.cute.llm.CuteToolDefinition;
import com.stioc.cute.tool.access.CuteTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 组装大模型 Options 选项的工厂，解耦具体大模型提供商的底层 Options 实现
 */
@Component
public class ChatOptionsFactory {

    /**
     * 根据当前会话配置动态构建自定义的 CuteChatOptions 选项
     *
     * @param activeConfig  当前激活的提供商模型配置
     * @param cuteTools     被允许调用的工具对象列表
     * @return 统一拼装后的 CuteChatOptions 实例
     */
    public CuteChatOptions buildOptions(Provider activeConfig, List<CuteTool> cuteTools) {
        double temperature = activeConfig.getTemperature() != null ? activeConfig.getTemperature() : 0.7;
        String modelName = activeConfig.getModelName();
        
        List<CuteToolDefinition> tools = cuteTools.stream()
                .map(tool -> {
                    String schema = tool.getArgumentSchema();
                    if (schema == null || schema.trim().isEmpty() || "{}".equals(schema.trim())) {
                        schema = "{\"type\":\"object\",\"properties\":{}}";
                    }
                    return CuteToolDefinition.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .inputSchema(schema)
                            .build();
                })
                .collect(Collectors.toList());

        return CuteChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .maxTokens(activeConfig.getMaxTokens())
                .reasoningEffort(activeConfig.getReasoningEffort())
                .tools(tools)
                .build();
    }
}
