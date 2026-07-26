package com.stioc.cute.agent.access;

import lombok.AllArgsConstructor;
import lombok.Data;
import okhttp3.Call;

/**
 * 封装会话运行中活跃的大模型 HTTP 请求元数据
 */
@Data
@AllArgsConstructor
public class ActiveLlmCall {

    /**
     * 大模型调用的唯一 ID
     */
    private final String llmCallId;

    /**
     * 所属会话 ID
     */
    private final Long cid;

    /**
     * OkHttp 连接实例引用
     */
    private final Call call;

    /**
     * 调用的模型名称 (如 claude-3-5-sonnet, gpt-4o)
     */
    private final String model;

    /**
     * 请求发起的时间戳（毫秒）
     */
    private final long startTime;
}
