package com.stioc.cute.engine.llm;

/**
 * SSE 流中收到上游错误事件帧时抛出的专用异常（SSE Error Frame Exception）。
 * <p>
 * 语义上区别于"单帧 JSON 解析失败可跳过"的普通解析异常：
 * 错误帧代表上游 API 主动报告调用失败（如网关 502、上游流中断等），
 * 本异常必须立即中断 SSE 流的迭代并向调用方传播，
 * 以便外层透明重试装饰器（CuteChatRetryWrapper）能感知失败并触发重试。
 * 若被迭代器的"跳过"逻辑静默吞掉，会导致失败被伪装成正常的空响应，
 * 进而在上层被误标为 SUCCESS 终态。
 * </p>
 */
public class SseErrorFrameException extends RuntimeException {

    public SseErrorFrameException(String message) {
        super(message);
    }

    public SseErrorFrameException(String message, Throwable cause) {
        super(message, cause);
    }
}
