package com.stioc.cute.engine.loop.message;

import com.stioc.cute.engine.loop.types.MessagePayload;

/**
 * 消息拦截器供血接口（宿主实现）。
 * <p>
 * 历史重建时，所有即将发往大模型的消息（USER/ASSISTANT/TOOL/BRANCH/COMPRESSED，
 * 含引擎合成的 SYSTEM 首条）都会按 order 升序经过拦截器链，宿主可自由改写正文与附件
 * （如用户消息追加时间戳、附件装载/占位、工具结果附件还原等），
 * 引擎仅负责编排调用，不感知具体加工实现；宿主按 {@code thisMsg.getRole()} 自行过滤适用范围。
 * </p>
 */
public interface MessageInterceptor {

    /**
     * 执行顺序，小者先执行（同序按注入顺序），默认 100
     */
    default int order() {
        return 100;
    }

    /**
     * 拦截加工单条即将发往大模型的消息
     *
     * @param payload 消息载体（正文/附件可改写，溯源元数据只读）
     */
    void intercept(MessagePayload payload);
}
