package com.stioc.cute.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * 基于原生 OkHttp 实现的 OpenAI 协议兼容大模型客户端。
 * 兼容所有 OpenAI API 格式的服务（OpenAI、DeepSeek、通义千问、Kimi 等）。
 */
@Slf4j
public class CuteChatForOpenAi extends AbstractCuteChat {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final Boolean useFullUrl;

    /**
     * 构造 OpenAI 协议客户端实例
     */
    public CuteChatForOpenAi(String baseUrl, String apiKey, String modelName, Double temperature,
                             Interceptor loggingInterceptor) {
        this(baseUrl, apiKey, modelName, temperature, false, loggingInterceptor);
    }

    /**
     * 构造 OpenAI 协议客户端实例（包含 useFullUrl 控制）
     */
    public CuteChatForOpenAi(String baseUrl, String apiKey, String modelName, Double temperature,
                             Boolean useFullUrl, Interceptor loggingInterceptor) {
        super(baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL,
                apiKey, modelName, temperature, loggingInterceptor);
        this.useFullUrl = useFullUrl;
    }

    // ──────────────────────────────────────────────
    // 协议实现
    // ──────────────────────────────────────────────

    @Override
    protected String protocolName() {
        return "OpenAI";
    }

    @Override
    protected Iterator<CuteChatResponse> createSseIterator(BufferedReader reader) {
        return new OpenAiSseIterator(reader);
    }

    // ──────────────────────────────────────────────
    // SSE Iterator（无副作用构造，懒初始化）
    // ──────────────────────────────────────────────

    /**
     * 同步逐行读取 OpenAI SSE 流的 Iterator。
     *
     * <p>设计要点：
     * <ul>
     *   <li>懒初始化：构造函数不做任何 I/O，第一次 {@link #hasNext()} 时才开始读取。</li>
     *   <li>幂等性：{@link #hasNext()} 可重复调用，不会丢失数据。</li>
     *   <li>工具调用参数在整个流结束后作为最后一个元素发出（汇总帧），保证调用方
     *       拿到完整的 arguments 字符串。</li>
     *   <li>资源释放由外层 {@link java.util.stream.Stream#onClose} 负责，Iterator 本身不持有关闭职责。</li>
     * </ul>
     */
    private static class OpenAiSseIterator implements Iterator<CuteChatResponse> {

        private final BufferedReader reader;
        private final List<ToolCallBuffer> toolCallBuffers = new ArrayList<>();
        private CuteUsage pendingUsage = null;

        private CuteChatResponse nextItem = null;
        private boolean streamDone = false;
        private boolean summaryEmitted = false;

        OpenAiSseIterator(BufferedReader reader) {
            this.reader = reader;
        }

        /**
         * 幂等：如果 nextItem 已经准备好，直接返回 true；否则尝试预取一个元素。
         */
        @Override
        public boolean hasNext() {
            if (nextItem != null) return true;
            if (streamDone) {
                emitSummaryIfNeeded();
                return nextItem != null;
            }
            advance();
            return nextItem != null;
        }

        @Override
        public CuteChatResponse next() {
            if (!hasNext()) throw new NoSuchElementException();
            CuteChatResponse current = nextItem;
            nextItem = null;
            return current;
        }

        private void advance() {
            if (streamDone) {
                emitSummaryIfNeeded();
                return;
            }

            try {
                String line;
                boolean receivedDone = false;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();

                    if ("[DONE]".equals(data)) {
                        receivedDone = true;
                        streamDone = true;
                        emitSummaryIfNeeded();
                        return;
                    }
                    if (data.isEmpty()) continue;

                    try {
                        CuteChatResponse chunk = parseChunk(JSON.parseObject(data));
                        if (chunk != null) {
                            nextItem = chunk;
                            return;
                        }
                    } catch (Exception e) {
                        log.error("解析 OpenAI SSE 帧失败，跳过: data={}, error={}", data, e.getMessage(), e);
                    }
                }
                // readLine() 返回 null：连接关闭或流结束（无 [DONE]）
                streamDone = true;
                if (!receivedDone) {
                    log.warn("OpenAI 流式响应未接收到 [DONE] 结束符，连接已提前关闭");
                }
                emitSummaryIfNeeded();
            } catch (IOException e) {
                streamDone = true;
                log.error("读取 OpenAI SSE 流失败", e);
                throw new RuntimeException("读取 OpenAI SSE 流失败", e);
            }
        }

        private void emitSummaryIfNeeded() {
            if (summaryEmitted) return;
            summaryEmitted = true;

            if (!toolCallBuffers.isEmpty()) {
                List<CuteToolCall> toolCalls = new ArrayList<>();
                for (ToolCallBuffer buf : toolCallBuffers) {
                    toolCalls.add(CuteToolCall.builder()
                            .id(buf.id).name(buf.name).arguments(buf.arguments.toString())
                            .build());
                }
                nextItem = CuteChatResponse.builder()
                        .toolCalls(toolCalls)
                        .usage(pendingUsage)
                        .build();
            } else if (pendingUsage != null) {
                nextItem = CuteChatResponse.builder().usage(pendingUsage).build();
            }
        }

        private CuteChatResponse parseChunk(JSONObject json) {
            // 优先检查 error 字段以防止流式中途出错被忽略
            JSONObject errorObj = json.getJSONObject("error");
            if (errorObj != null) {
                String errorMessage = errorObj.getString("message");
                log.error("OpenAI 流式 API 调用过程中返回错误: {}", errorMessage);
                throw new RuntimeException("OpenAI 流式 API 报错: " + errorMessage);
            }

            JSONObject usageObj = json.getJSONObject("usage");
            if (usageObj != null) {
                pendingUsage = parseUsage(usageObj);
            }

            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;

            JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
            if (delta == null) return null;

            String content = delta.getString("content");
            String reasoning = delta.getString("reasoning_content");

            JSONArray toolCallsArr = delta.getJSONArray("tool_calls");
            if (toolCallsArr != null) {
                for (int i = 0; i < toolCallsArr.size(); i++) {
                    JSONObject tc = toolCallsArr.getJSONObject(i);
                    int index = tc.getIntValue("index", i);
                    while (toolCallBuffers.size() <= index) toolCallBuffers.add(new ToolCallBuffer());
                    ToolCallBuffer buf = toolCallBuffers.get(index);
                    String id = tc.getString("id");
                    if (id != null && !id.isBlank()) {
                        buf.id = id;
                    }
                    JSONObject func = tc.getJSONObject("function");
                    if (func != null) {
                        String name = func.getString("name");
                        if (name != null && !name.isBlank()) {
                            buf.name = name;
                        }
                        String args = func.getString("arguments");
                        if (args != null) {
                            buf.arguments.append(args);
                        }
                    }
                }
                if (content == null && reasoning == null) return null;
            }

            // 过滤空字符串，与 Anthropic 侧行为保持一致
            String effectiveContent = (content != null && !content.isEmpty()) ? content : null;
            String effectiveReasoning = (reasoning != null && !reasoning.isEmpty()) ? reasoning : null;
            if (effectiveContent != null || effectiveReasoning != null) {
                return CuteChatResponse.builder().content(effectiveContent).reasoningContent(effectiveReasoning).build();
            }
            return null;
        }
    }

    // ──────────────────────────────────────────────
    // 请求构建
    // ──────────────────────────────────────────────

    @Override
    protected String buildRequestBody(CutePrompt prompt, boolean stream) {
        JSONObject body = new JSONObject();
        body.put("model", resolveModel(prompt));
        body.put("stream", stream);

        // OpenAI 协议默认传递 temperature，未显式设置时使用 0.7
        Double temp = resolveTemperature(prompt);
        body.put("temperature", temp != null ? temp : 0.7);

        if (prompt.getOptions() != null) {
            if (prompt.getOptions().getMaxTokens() != null) {
                body.put("max_tokens", prompt.getOptions().getMaxTokens());
            }
            if (prompt.getOptions().getReasoningEffort() != null && !prompt.getOptions().getReasoningEffort().isBlank()) {
                body.put("reasoning_effort", prompt.getOptions().getReasoningEffort().trim());
            }
        }

        // 流式模式下显式请求在最后一帧携带 usage，确保 token 统计可用
        if (stream) {
            JSONObject streamOptions = new JSONObject();
            streamOptions.put("include_usage", true);
            body.put("stream_options", streamOptions);
        }

        JSONArray messages = new JSONArray();
        for (CuteMessage msg : prompt.getMessages()) {
            JSONObject converted = convertMessage(msg);
            if (converted != null) {
                messages.add(converted);
            } else {
                log.warn("消息转换结果为 null，已跳过: role={}", msg.getRole());
            }
        }
        body.put("messages", messages);

        if (prompt.getOptions() != null
                && prompt.getOptions().getTools() != null
                && !prompt.getOptions().getTools().isEmpty()) {
            JSONArray tools = new JSONArray();
            for (CuteToolDefinition tool : prompt.getOptions().getTools()) {
                JSONObject toolObj = new JSONObject();
                toolObj.put("type", "function");
                JSONObject func = new JSONObject();
                func.put("name", tool.getName());
                func.put("description", tool.getDescription());
                String schema = tool.getInputSchema();
                if (schema == null || schema.isBlank() || "{}".equals(schema.trim())) {
                    schema = "{\"type\":\"object\",\"properties\":{}}";
                }
                func.put("parameters", JSON.parseObject(schema));
                toolObj.put("function", func);
                tools.add(toolObj);
            }
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        return JSON.toJSONString(body, JSONWriter.Feature.WriteNulls);
    }

    private JSONObject convertMessage(CuteMessage msg) {
        JSONObject obj = new JSONObject();
        switch (msg.getRole()) {
            case SYSTEM -> {
                obj.put("role", "system");
                obj.put("content", msg.getContent() != null ? msg.getContent() : "");
            }
            case USER -> {
                obj.put("role", "user");
                if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                    JSONArray parts = new JSONArray();
                    if (StringUtils.hasText(msg.getContent())) {
                        JSONObject textPart = new JSONObject();
                        textPart.put("type", "text");
                        textPart.put("text", msg.getContent());
                        parts.add(textPart);
                    }
                    for (CuteAttachment att : msg.getAttachments()) {
                        if (att.isImage() && StringUtils.hasText(att.getBase64Data())) {
                            JSONObject imgPart = new JSONObject();
                            imgPart.put("type", "image_url");
                            JSONObject urlObj = new JSONObject();
                            String mime = StringUtils.hasText(att.getMimeType()) ? att.getMimeType() : "image/jpeg";
                            urlObj.put("url", "data:" + mime + ";base64," + att.getBase64Data());
                            imgPart.put("image_url", urlObj);
                            parts.add(imgPart);
                        } else if (StringUtils.hasText(att.getTextContent())) {
                            JSONObject textPart = new JSONObject();
                            textPart.put("type", "text");
                            textPart.put("text", "\n\n[附件文件: " + att.getName() + "]\n" + att.getTextContent());
                            parts.add(textPart);
                        }
                    }
                    obj.put("content", parts);
                } else {
                    obj.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
            }
            case ASSISTANT -> {
                obj.put("role", "assistant");
                // OpenAI 协议要求 assistant 消息始终包含 content 字段（可为 null），
                // 尤其是存在 tool_calls 时，部分兼容 API 对此有严格校验
                obj.put("content", msg.getContent());
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    JSONArray arr = new JSONArray();
                    for (CuteToolCall tc : msg.getToolCalls()) {
                        JSONObject tcObj = new JSONObject();
                        tcObj.put("id", tc.getId());
                        tcObj.put("type", "function");
                        JSONObject funcObj = new JSONObject();
                        funcObj.put("name", tc.getName());
                        funcObj.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
                        tcObj.put("function", funcObj);
                        arr.add(tcObj);
                    }
                    obj.put("tool_calls", arr);
                }
            }
            case TOOL -> {
                obj.put("role", "tool");
                obj.put("tool_call_id", msg.getToolCallId());
                if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                    JSONArray parts = new JSONArray();
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        JSONObject textPart = new JSONObject();
                        textPart.put("type", "text");
                        textPart.put("text", msg.getContent());
                        parts.add(textPart);
                    }
                    for (CuteAttachment att : msg.getAttachments()) {
                        if (att.isImage() && att.getBase64Data() != null) {
                            JSONObject imgPart = new JSONObject();
                            imgPart.put("type", "image_url");
                            JSONObject urlObj = new JSONObject();
                            String mime = att.getMimeType() != null ? att.getMimeType() : "image/jpeg";
                            urlObj.put("url", "data:" + mime + ";base64," + att.getBase64Data());
                            imgPart.put("image_url", urlObj);
                            parts.add(imgPart);
                        } else if (att.getTextContent() != null && !att.getTextContent().isEmpty()) {
                            JSONObject textPart = new JSONObject();
                            textPart.put("type", "text");
                            textPart.put("text", "\n\n[附件文件: " + att.getName() + "]\n" + att.getTextContent());
                            parts.add(textPart);
                        }
                    }
                    obj.put("content", parts);
                } else {
                    obj.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
            }
            default -> {
                log.warn("未知消息角色，已跳过: role={}", msg.getRole());
                return null;
            }
        }
        return obj;
    }

    @Override
    protected Request buildHttpRequest(String bodyJson) {
        String url = Boolean.TRUE.equals(useFullUrl) ? baseUrl : (baseUrl + "/chat/completions");
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", "st-cute-code-agent")
                .post(RequestBody.create(bodyJson, JSON_MEDIA_TYPE))
                .build();
    }

    // ──────────────────────────────────────────────
    // 响应解析（非流式）
    // ──────────────────────────────────────────────

    @Override
    protected CuteChatResponse parseNonStreamResponse(String responseBody) {
        JSONObject json = JSON.parseObject(responseBody);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            JSONObject errorObj = json.getJSONObject("error");
            if (errorObj != null) {
                String errorMessage = errorObj.getString("message");
                log.error("OpenAI API 调用失败: {}", errorMessage);
                throw new RuntimeException("OpenAI API 调用失败: " + errorMessage);
            }
            log.warn("OpenAI API 响应中 choices 为空，返回空结果");
            return CuteChatResponse.builder().build();
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) {
            log.warn("OpenAI API 响应中 message 为空，返回空结果");
            return CuteChatResponse.builder().build();
        }

        String content = message.getString("content");
        String reasoning = message.getString("reasoning_content");
        List<CuteToolCall> toolCalls = parseToolCallsFromMessage(message);
        CuteUsage usage = parseUsage(json.getJSONObject("usage"));

        return CuteChatResponse.builder()
                .content(content)
                .reasoningContent(reasoning)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .usage(usage)
                .build();
    }

    private List<CuteToolCall> parseToolCallsFromMessage(JSONObject message) {
        List<CuteToolCall> list = new ArrayList<>();
        JSONArray arr = message.getJSONArray("tool_calls");
        if (arr == null) return list;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject tc = arr.getJSONObject(i);
            JSONObject func = tc.getJSONObject("function");
            if (func == null) continue;
            list.add(CuteToolCall.builder()
                    .id(tc.getString("id"))
                    .name(func.getString("name"))
                    .arguments(func.getString("arguments"))
                    .build());
        }
        return list;
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private static CuteUsage parseUsage(JSONObject usageObj) {
        if (usageObj == null) return null;
        long cachedTokens = 0L;
        JSONObject promptDetails = usageObj.getJSONObject("prompt_tokens_details");
        if (promptDetails != null) {
            cachedTokens = promptDetails.getLongValue("cached_tokens", 0L);
        }
        return CuteUsage.builder()
                .inputTokens(usageObj.getLongValue("prompt_tokens", 0L))
                .outputTokens(usageObj.getLongValue("completion_tokens", 0L))
                .cachedTokens(cachedTokens)
                .build();
    }
}
