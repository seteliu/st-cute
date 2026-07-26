package com.stioc.cute.llm;

import lombok.Builder;
import lombok.Data;
import okhttp3.Call;
import java.util.List;
import java.util.function.Consumer;

/**
 * 大模型调用提示词包装实体类
 */
@Data
@Builder
public class CutePrompt {

    /**
     * 历史上下文消息列表
     */
    private final List<CuteMessage> messages;

    /**
     * 大模型调用参数配置项
     */
    private final CuteChatOptions options;

    /**
     * 大模型请求 Call 创建后的监听回调钩子
     */
    private final Consumer<Call> callListener;
}
