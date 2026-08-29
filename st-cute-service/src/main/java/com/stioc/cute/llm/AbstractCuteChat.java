package com.stioc.cute.llm;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * CuteChat 公共基类，提取两种协议实现中重复的基础设施代码：
 * <ul>
 *   <li>OkHttpClient 构建（含 CookieJar、超时、拦截器）</li>
 *   <li>URL 规范化</li>
 *   <li>model / temperature 解析</li>
 *   <li>SSE 流打开与资源管理</li>
 * </ul>
 */
@Slf4j
abstract class AbstractCuteChat implements CuteChat {

    protected final String baseUrl;
    protected final String apiKey;
    protected final String modelName;
    /** 温度参数，null 表示未显式设置（由子类决定是否传递给 API） */
    protected final Double temperature;
    protected final OkHttpClient httpClient;

    protected AbstractCuteChat(String rawBaseUrl, String apiKey, String modelName,
                                Double temperature, okhttp3.Interceptor loggingInterceptor) {
        this.baseUrl = normalizeBaseUrl(rawBaseUrl);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature;
        this.httpClient = buildHttpClient(loggingInterceptor);
    }

    // ──────────────────────────────────────────────
    // OkHttpClient 构建
    // ──────────────────────────────────────────────

    /** 共享的基础 OkHttpClient，复用连接池和线程池 */
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .cookieJar(buildCookieJar())
            .build();

    private static OkHttpClient buildHttpClient(okhttp3.Interceptor loggingInterceptor) {
        if (loggingInterceptor == null) {
            return BASE_CLIENT;
        }
        // 基于共享客户端派生，复用连接池和线程池
        return BASE_CLIENT.newBuilder()
                .addInterceptor(loggingInterceptor)
                .build();
    }

    /**
     * 基于内存的简易 CookieJar，用于回传 WAF/网关下发的会话 Cookie（如 acw_tc），
     * 并在读取时过滤已过期的条目。
     */
    private static CookieJar buildCookieJar() {
        return new CookieJar() {
            private final Map<String, List<Cookie>> store = new ConcurrentHashMap<>();

            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                long now = System.currentTimeMillis();
                // 合并而非覆盖，按 cookie name 去重，新 cookie 优先，顺便过滤掉已过期的 cookie
                store.compute(url.host(), (host, existing) -> {
                    Map<String, Cookie> merged = new HashMap<>();
                    if (existing != null) {
                        for (Cookie c : existing) {
                            if (c.expiresAt() > now) {
                                merged.put(c.name(), c);
                            }
                        }
                    }
                    for (Cookie c : cookies) {
                        if (c.expiresAt() > now) {
                            merged.put(c.name(), c);
                        }
                    }
                    return merged.isEmpty() ? null : new ArrayList<>(merged.values());
                });
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = store.get(url.host());
                if (cookies == null) return new ArrayList<>();

                long now = System.currentTimeMillis();
                List<Cookie> valid = new ArrayList<>();
                // 仅读取并过滤有效 Cookie，不写回 store，避免并发覆盖竞态条件
                for (Cookie c : cookies) {
                    if (c.expiresAt() > now) {
                        valid.add(c);
                    }
                }
                return valid;
            }
        };
    }

    // ──────────────────────────────────────────────
    // 子类必须实现的协议相关方法
    // ──────────────────────────────────────────────

    /** 构建 HTTP 请求体 JSON */
    protected abstract String buildRequestBody(CutePrompt prompt, boolean stream);

    /** 构建带认证头的 HTTP 请求 */
    protected abstract Request buildHttpRequest(String bodyJson);

    /** 创建协议对应的 SSE 事件迭代器 */
    protected abstract Iterator<CuteChatResponse> createSseIterator(BufferedReader reader);

    /** 解析非流式响应体 */
    protected abstract CuteChatResponse parseNonStreamResponse(String responseBody);

    /** 协议名称，用于日志和错误消息 */
    protected abstract String protocolName();

    // ──────────────────────────────────────────────
    // CuteChat 公共实现
    // ──────────────────────────────────────────────

    @Override
    public CuteChatResponse call(CutePrompt prompt) {
        String bodyJson = buildRequestBody(prompt, false);
        Request request = buildHttpRequest(bodyJson);
        Call call = httpClient.newCall(request);

        if (prompt.getCallListener() != null) {
            prompt.getCallListener().accept(call);
        }

        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            String responseBody = body != null ? body.string() : "{}";

            if (!response.isSuccessful()) {
                log.error("{} API 请求失败: HTTP {}, response={}", protocolName(), response.code(), responseBody);
                throw new RuntimeException(protocolName() + " API 请求失败，HTTP " + response.code() + ": " + responseBody);
            }
            return parseNonStreamResponse(responseBody);
        } catch (IOException e) {
            log.error("{} API 网络请求异常", protocolName(), e);
            throw new RuntimeException(protocolName() + " API 网络请求异常", e);
        }
    }

    @Override
    public void streamConsume(CutePrompt prompt, Consumer<Stream<CuteChatResponse>> consumer) {
        try (Stream<CuteChatResponse> stream = openStream(prompt)) {
            consumer.accept(stream);
        }
    }

    /**
     * 打开 SSE 流并返回懒加载的 Stream。
     * 资源释放由 {@link Stream#onClose} 回调负责，调用方必须通过 try-with-resources 使用。
     */
    private Stream<CuteChatResponse> openStream(CutePrompt prompt) {
        String bodyJson = buildRequestBody(prompt, true);
        Request request = buildHttpRequest(bodyJson);
        Call call = httpClient.newCall(request);

        if (prompt.getCallListener() != null) {
            prompt.getCallListener().accept(call);
        }

        Response response = null;
        boolean success = false;
        try {
            response = call.execute();
            if (!response.isSuccessful()) {
                ResponseBody errorBody = response.body();
                String errorText;
                try {
                    errorText = errorBody != null ? errorBody.string() : "(empty)";
                } catch (IOException ex) {
                    errorText = "(read error)";
                }
                log.error("{} 流式 API 请求失败: HTTP {}, response={}", protocolName(), response.code(), errorText);
                throw new RuntimeException(protocolName() + " 流式 API 请求失败，HTTP " + response.code() + ": " + errorText);
            }

            ResponseBody body = response.body();
            if (body == null) {
                log.error("{} 流式响应体为空", protocolName());
                throw new RuntimeException(protocolName() + " 流式响应体为空");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));

            Iterator<CuteChatResponse> iterator = createSseIterator(reader);

            Response finalResponse = response;
            Stream<CuteChatResponse> stream = StreamSupport
                    .stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false)
                    .onClose(() -> {
                        try { reader.close(); } catch (IOException ignored) {}
                        finalResponse.close();
                    });
            success = true;
            return stream;
        } catch (IOException e) {
            log.error("{} 流式请求发起失败", protocolName(), e);
            throw new RuntimeException(protocolName() + " 流式请求发起失败", e);
        } finally {
            if (!success && response != null) {
                response.close();
            }
        }
    }

    // ──────────────────────────────────────────────
    // 公共工具方法
    // ──────────────────────────────────────────────

    protected String resolveModel(CutePrompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            return prompt.getOptions().getModel();
        }
        return modelName;
    }

    /**
     * 解析温度参数。
     * 返回 null 表示未显式设置，由子类决定是否传递给 API。
     */
    protected Double resolveTemperature(CutePrompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getTemperature() != null) {
            return prompt.getOptions().getTemperature();
        }
        return temperature;
    }

    protected int resolveMaxTokens(CutePrompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getMaxTokens() != null) {
            return prompt.getOptions().getMaxTokens();
        }
        return 16384;
    }

    protected static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) return null; // 子类各自提供默认值
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ──────────────────────────────────────────────
    // 内部共用数据结构
    // ──────────────────────────────────────────────

    /** 流式 tool call 参数累积缓冲区 */
    protected static class ToolCallBuffer {
        String id = "";
        String name = "";
        StringBuilder arguments = new StringBuilder();
    }
}
