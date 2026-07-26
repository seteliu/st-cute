package com.stioc.cute.llm;

import com.stioc.cute.agent.access.LlmLoggerService;
import okhttp3.*;
import okio.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OkHttp 请求与响应日志拦截器
 * 用于拦截大模型通信的原始 HTTP 流量并写入日志
 */
public class OkHttpLoggingInterceptor implements Interceptor {

    private static final long MAX_SSE_BUFFER_BYTES = 20L * 1024 * 1024; // 20MB

    private final LlmLoggerService llmLoggerService;

    public OkHttpLoggingInterceptor(LlmLoggerService llmLoggerService) {
        this.llmLoggerService = llmLoggerService;
    }

    /**
     * 拦截 HTTP 请求流并透明记录请求体与响应流
     */
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        if (!llmLoggerService.isHttpLogEnabled()) {
            return chain.proceed(request);
        }

        String uuid = java.util.UUID.randomUUID().toString();
        String url = request.url().toString();
        String method = request.method();
        Map<String, List<String>> requestHeaders = request.headers().toMultimap();
        String requestBody = readRequestBody(request);

        llmLoggerService.writeRawHttpRequest(uuid, url, method, requestHeaders, requestBody);

        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            llmLoggerService.writeRawHttpError(uuid, url, e);
            throw e;
        }

        int code = response.code();
        Map<String, List<String>> responseHeaders = response.headers().toMultimap();

        ResponseBody body = response.body();
        boolean isStream = body != null
                && body.contentType() != null
                && body.contentType().toString().toLowerCase().contains("text/event-stream");

        if (isStream) {
            // 用 SpyResponseBody 包装：调用方读取 SSE 数据时同步截获，流关闭时一次性写入完整日志
            ResponseBody spyBody = new SpyResponseBody(body, completeBody ->
                    llmLoggerService.writeRawHttpStreamComplete(uuid, url, code, responseHeaders, completeBody));
            return response.newBuilder().body(spyBody).build();
        } else {
            String responseBodyStr = "";
            if (body != null) {
                try {
                    responseBodyStr = response.peekBody(1024 * 1024 * 4).string();
                } catch (Exception e) {
                    responseBodyStr = "[Error reading response body: " + e.getMessage() + "]";
                }
            }
            llmLoggerService.writeRawHttpResponse(uuid, url, code, responseHeaders, responseBodyStr, false);
            return response;
        }
    }

    // ──────────────────────────────────────────────
    // SpyResponseBody：透明代理 ResponseBody，流关闭时触发完整内容回调
    // ──────────────────────────────────────────────

    /**
     * SSE 响应体代理类，用于在流关闭时回调完整读取的内容
     */
    private static class SpyResponseBody extends ResponseBody {

        private final ResponseBody delegate;
        private final Consumer<String> onComplete;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated = false;
        private BufferedSource spySource;

        SpyResponseBody(ResponseBody delegate, Consumer<String> onComplete) {
            this.delegate = delegate;
            this.onComplete = onComplete;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength(); // SSE 通常返回 -1
        }

        @Override
        public BufferedSource source() {
            if (spySource == null) {
                Source forwardingSource = new ForwardingSource(delegate.source()) {
                    @Override
                    public long read(Buffer sink, long byteCount) throws IOException {
                        long bytesRead = super.read(sink, byteCount);
                        if (bytesRead > 0 && !truncated) {
                            long currentSize = buffer.size();
                            if (currentSize + bytesRead > MAX_SSE_BUFFER_BYTES) {
                                // 只截取还能放下的部分，其余标记 truncated
                                long remaining = MAX_SSE_BUFFER_BYTES - currentSize;
                                if (remaining > 0) {
                                    Buffer peek = new Buffer();
                                    sink.copyTo(peek, sink.size() - bytesRead, remaining);
                                    buffer.write(peek.readByteArray());
                                }
                                buffer.write("\n[truncated: SSE body exceeded 20MB limit]".getBytes(StandardCharsets.UTF_8));
                                truncated = true;
                            } else {
                                Buffer peek = new Buffer();
                                sink.copyTo(peek, sink.size() - bytesRead, bytesRead);
                                buffer.write(peek.readByteArray());
                            }
                        }
                        return bytesRead;
                    }
                };
                spySource = Okio.buffer(forwardingSource);
            }
            return spySource;
        }

        @Override
        public void close() {
            try {
                onComplete.accept(buffer.toString(StandardCharsets.UTF_8));
            } finally {
                delegate.close();
            }
        }
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    /**
     * 读取并以字符串形式返回请求体内容
     */
    private String readRequestBody(Request request) {
        try {
            Buffer buffer = new Buffer();
            if (request.body() != null) {
                request.body().writeTo(buffer);
                return buffer.readUtf8();
            }
        } catch (IOException ignored) {
        }
        return "";
    }
}
