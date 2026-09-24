package com.stioc.cute.engine.llm;

import java.util.List;
import java.util.Map;

/**
 * 引擎大模型 HTTP 日志供血接口（宿主实现）。
 * <p>
 * OkHttp 拦截器产生的原始 HTTP 请求/响应日志（含敏感信息脱敏与滚动文件管理）
 * 属宿主观测设施，引擎经本接口回调写日志，不感知日志落盘位置与开关存储。
 * </p>
 */
public interface LlmHttpLogger {

    /**
     * 返回当前是否启用 HTTP 日志记录。
     * 调用方可先判断此开关再决定是否构造日志相关逻辑，避免无谓的对象创建。
     */
    boolean isHttpLogEnabled();

    /**
     * 返回当前是否记录响应部分（含 SSE 流式响应全文）。
     * 关闭时拦截器仅记录请求报文与异常，响应体完全不缓冲，避免流式日志把文件冲爆。
     */
    boolean isHttpLogResponseIncluded();

    /**
     * 写入原始 HTTP 请求日志
     */
    void writeRawHttpRequest(String uuid, String url, String method, Map<String, List<String>> headers, String body);

    /**
     * 写入原始 HTTP 响应日志
     */
    void writeRawHttpResponse(String uuid, String url, int code, Map<String, List<String>> headers, String body, boolean isStream);

    /**
     * 写入 SSE 流式响应完整 body 日志（流关闭后触发）
     */
    void writeRawHttpStreamComplete(String uuid, String url, int code, Map<String, List<String>> headers, String completeStream);

    /**
     * 写入原始 HTTP 异常日志
     */
    void writeRawHttpError(String uuid, String url, Throwable t);
}
