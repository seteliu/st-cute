package com.stioc.cute.llm;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 大模型对话客户端统一契约接口
 * 提供基础的对话调用与流式处理能力
 * 用于对接各类大模型服务商
 */
public interface CuteChat {

    /**
     * 阻塞同步发起大模型单次调用
     */
    CuteChatResponse call(CutePrompt prompt);

    /**
     * 流式调用唯一入口，内部负责自动关闭底层 HTTP 连接
     */
    void streamConsume(CutePrompt prompt, Consumer<Stream<CuteChatResponse>> consumer);
}
