package com.stioc.cute.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 基于原生 OkHttp 实现的 OpenAI Response 协议大模型客户端。
 * 适配 OpenAI 新一代 Responses API 规范（POST /v1/responses）。
 */
@Slf4j
public class CuteChatForOpenAiResponse extends AbstractCuteChat {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final Boolean useFullUrl;

    /**
     * 构造 OpenAI Response 协议客户端实例
     */
    public CuteChatForOpenAiResponse(String baseUrl, String apiKey, String modelName, Double temperature,
                                     Interceptor loggingInterceptor) {
        this(baseUrl, apiKey, modelName, temperature, false, loggingInterceptor);
    }

    /**
     * 构造 OpenAI Response 协议客户端实例（包含 useFullUrl 控制）
     */
    public CuteChatForOpenAiResponse(String baseUrl, String apiKey, String modelName, Double temperature,
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
        return "OpenAI-Response";
    }

    @Override
    protected Iterator<CuteChatResponse> createSseIterator(BufferedReader reader) {
        return new OpenAiResponseSseIterator(reader);
    }

    // ──────────────────────────────────────────────
    // 请求构建
    // ──────────────────────────────────────────────

    @Override
    protected String buildRequestBody(CutePrompt prompt, boolean stream) {
        JSONObject body = new JSONObject();
        body.put("model", resolveModel(prompt));
        body.put("stream", stream);

        Double temp = resolveTemperature(prompt);
        body.put("temperature", temp != null ? temp : 0.7);

        if (prompt.getOptions() != null) {
            if (prompt.getOptions().getMaxTokens() != null) {
                body.put("max_output_tokens", prompt.getOptions().getMaxTokens());
            }
            if (prompt.getOptions().getReasoningEffort() != null && !prompt.getOptions().getReasoningEffort().isBlank()) {
                body.put("reasoning_effort", prompt.getOptions().getReasoningEffort().trim());
            }
        }

        // 提取系统提示词放入 instructions 字段
        StringBuilder instructionsBuilder = new StringBuilder();
        JSONArray inputItems = new JSONArray();

        for (CuteMessage msg : prompt.getMessages()) {
            if (msg.getRole() == CuteMessageRole.SYSTEM) {
                if (StringUtils.hasText(msg.getContent())) {
                    if (!instructionsBuilder.isEmpty()) {
                        instructionsBuilder.append("\n\n");
                    }
                    instructionsBuilder.append(msg.getContent());
                }
            } else {
                convertAndAppendInputItems(msg, inputItems);
            }
        }

        if (!instructionsBuilder.isEmpty()) {
            body.put("instructions", instructionsBuilder.toString());
        }
        body.put("input", inputItems);

        // 工具列表转换
        if (prompt.getOptions() != null
                && prompt.getOptions().getTools() != null
                && !prompt.getOptions().getTools().isEmpty()) {
            JSONArray tools = new JSONArray();
            for (CuteToolDefinition tool : prompt.getOptions().getTools()) {
                JSONObject toolObj = new JSONObject();
                toolObj.put("type", "function");
                toolObj.put("name", tool.getName());
                toolObj.put("description", tool.getDescription() != null ? tool.getDescription() : "");
                String schema = tool.getInputSchema();
                if (schema == null || schema.isBlank() || "{}".equals(schema.trim())) {
                    schema = "{\"type\":\"object\",\"properties\":{}}";
                }
                toolObj.put("parameters", JSON.parseObject(schema));
                tools.add(toolObj);
            }
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        return JSON.toJSONString(body, JSONWriter.Feature.WriteNulls);
    }

    private void convertAndAppendInputItems(CuteMessage msg, JSONArray inputList) {
        switch (msg.getRole()) {
            case USER -> {
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                    JSONArray parts = new JSONArray();
                    if (StringUtils.hasText(msg.getContent())) {
                        JSONObject textPart = new JSONObject();
                        textPart.put("type", "input_text");
                        textPart.put("text", msg.getContent());
                        parts.add(textPart);
                    }
                    for (CuteAttachment att : msg.getAttachments()) {
                        boolean isImg = att.isImage() || (att.getMimeType() != null && att.getMimeType().startsWith("image/"));
                        if (isImg && StringUtils.hasText(att.getBase64Data())) {
                            JSONObject imgPart = new JSONObject();
                            imgPart.put("type", "input_image");
                            String mime = StringUtils.hasText(att.getMimeType()) ? att.getMimeType() : "image/jpeg";
                            imgPart.put("image_url", "data:" + mime + ";base64," + att.getBase64Data());
                            parts.add(imgPart);
                        } else if (StringUtils.hasText(att.getTextContent())) {
                            JSONObject textPart = new JSONObject();
                            textPart.put("type", "input_text");
                            textPart.put("text", "\n\n[附件文件: " + att.getName() + "]\n" + att.getTextContent());
                            parts.add(textPart);
                        }
                    }
                    userMsg.put("content", parts);
                } else {
                    userMsg.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
                inputList.add(userMsg);
            }
            case ASSISTANT -> {
                // 如果 assistant 携带了文本回复
                if (StringUtils.hasText(msg.getContent())) {
                    JSONObject asstMsg = new JSONObject();
                    asstMsg.put("role", "assistant");
                    asstMsg.put("content", msg.getContent());
                    inputList.add(asstMsg);
                }
                // 如果 assistant 触发了工具调用，Responses API 规范中每个 function_call 作为单独 item 传入 input
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    for (CuteToolCall tc : msg.getToolCalls()) {
                        JSONObject callObj = new JSONObject();
                        callObj.put("type", "function_call");
                        callObj.put("call_id", tc.getId());
                        callObj.put("name", tc.getName());
                        callObj.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
                        inputList.add(callObj);
                    }
                }
            }
            case TOOL -> {
                // Responses 协议中工具回传为 function_call_output 独立 item
                JSONObject toolResult = new JSONObject();
                toolResult.put("type", "function_call_output");
                toolResult.put("call_id", msg.getToolCallId());

                StringBuilder contentBuilder = new StringBuilder();
                if (StringUtils.hasText(msg.getContent())) {
                    contentBuilder.append(msg.getContent());
                }
                if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                    for (CuteAttachment att : msg.getAttachments()) {
                        if (StringUtils.hasText(att.getTextContent())) {
                            contentBuilder.append("\n\n[附件文件: ").append(att.getName()).append("]\n").append(att.getTextContent());
                        }
                    }
                }
                toolResult.put("output", contentBuilder.toString());
                inputList.add(toolResult);
            }
            default -> log.warn("未知消息角色，已跳过: role={}", msg.getRole());
        }
    }

    @Override
    protected Request buildHttpRequest(String bodyJson) {
        String url = Boolean.TRUE.equals(useFullUrl) ? baseUrl : (baseUrl + "/responses");
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
        JSONObject errorObj = json.getJSONObject("error");
        if (errorObj != null) {
            String errorMessage = errorObj.getString("message");
            log.error("OpenAI Response API 调用失败: {}", errorMessage);
            throw new RuntimeException("OpenAI Response API 调用失败: " + errorMessage);
        }

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<CuteToolCall> toolCalls = new ArrayList<>();

        JSONArray output = json.getJSONArray("output");
        if (output != null && !output.isEmpty()) {
            for (int i = 0; i < output.size(); i++) {
                JSONObject item = output.getJSONObject(i);
                if (item == null) continue;
                String type = item.getString("type");

                if ("message".equalsIgnoreCase(type) || "assistant".equalsIgnoreCase(item.getString("role"))) {
                    Object contentObj = item.get("content");
                    if (contentObj instanceof String str) {
                        contentBuilder.append(str);
                    } else if (contentObj instanceof JSONArray arr) {
                        for (int j = 0; j < arr.size(); j++) {
                            JSONObject part = arr.getJSONObject(j);
                            if (part != null) {
                                String text = part.getString("text");
                                if (StringUtils.hasText(text)) {
                                    contentBuilder.append(text);
                                }
                            }
                        }
                    }
                } else if ("function_call".equalsIgnoreCase(type)) {
                    String callId = item.getString("call_id");
                    if (!StringUtils.hasText(callId)) {
                        callId = item.getString("id");
                    }
                    String name = item.getString("name");
                    String arguments = item.getString("arguments");
                    if (arguments == null) {
                        Object argsObj = item.get("arguments");
                        arguments = argsObj != null ? JSON.toJSONString(argsObj) : "{}";
                    }
                    toolCalls.add(CuteToolCall.builder()
                            .id(callId)
                            .name(name)
                            .arguments(arguments)
                            .build());
                } else if ("reasoning".equalsIgnoreCase(type)) {
                    String reasoning = item.getString("content");
                    if (!StringUtils.hasText(reasoning)) {
                        reasoning = item.getString("text");
                    }
                    if (StringUtils.hasText(reasoning)) {
                        reasoningBuilder.append(reasoning);
                    }
                }
            }
        } else {
            // 兼容快捷字段
            String outputText = json.getString("output_text");
            if (StringUtils.hasText(outputText)) {
                contentBuilder.append(outputText);
            }
        }

        CuteUsage usage = parseUsage(json.getJSONObject("usage"));

        String finalContent = !contentBuilder.isEmpty() ? contentBuilder.toString() : null;
        String finalReasoning = !reasoningBuilder.isEmpty() ? reasoningBuilder.toString() : null;

        return CuteChatResponse.builder()
                .content(finalContent)
                .reasoningContent(finalReasoning)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .usage(usage)
                .build();
    }

    // ──────────────────────────────────────────────
    // SSE Iterator（流式读取）
    // ──────────────────────────────────────────────

    private static class OpenAiResponseSseIterator implements Iterator<CuteChatResponse> {

        private final BufferedReader reader;
        private final Map<Integer, ToolCallBuffer> bufferByOutputIndex = new LinkedHashMap<>();
        private final Map<String, ToolCallBuffer> bufferById = new LinkedHashMap<>();
        private final List<ToolCallBuffer> toolCallList = new ArrayList<>();
        private CuteUsage pendingUsage = null;

        private CuteChatResponse nextItem = null;
        private boolean streamDone = false;
        private boolean summaryEmitted = false;
        private String currentEventName = null;

        OpenAiResponseSseIterator(BufferedReader reader) {
            this.reader = reader;
        }

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

        private void emitSummaryIfNeeded() {
            if (summaryEmitted) return;
            summaryEmitted = true;

            List<CuteToolCall> toolCalls = new ArrayList<>();
            for (ToolCallBuffer buf : toolCallList) {
                if (StringUtils.hasText(buf.name)) {
                    String rawArgs = buf.arguments.toString().trim();
                    String finalArgs = rawArgs.isEmpty() ? "{}" : rawArgs;
                    toolCalls.add(CuteToolCall.builder()
                            .id(buf.id)
                            .name(buf.name)
                            .arguments(finalArgs)
                            .build());
                }
            }

            if (!toolCalls.isEmpty() || pendingUsage != null) {
                nextItem = CuteChatResponse.builder()
                        .toolCalls(!toolCalls.isEmpty() ? toolCalls : null)
                        .usage(pendingUsage)
                        .build();
            }
        }

        private void advance() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        currentEventName = null;
                        continue;
                    }

                    if (line.startsWith("event:")) {
                        currentEventName = line.substring(6).trim();
                        continue;
                    }

                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            streamDone = true;
                            emitSummaryIfNeeded();
                            return;
                        }

                        CuteChatResponse resp = processSseData(currentEventName, data);
                        if (resp != null) {
                            nextItem = resp;
                            return;
                        }
                    }
                }

                streamDone = true;
                emitSummaryIfNeeded();
            } catch (IOException e) {
                log.error("读取 OpenAI Response SSE 流失败", e);
                throw new RuntimeException("读取 OpenAI Response SSE 流失败", e);
            }
        }

        private CuteChatResponse processSseData(String eventName, String data) {
            try {
                JSONObject json = JSON.parseObject(data);
                if (json == null) return null;

                JSONObject errorObj = json.getJSONObject("error");
                if (errorObj != null) {
                    String errorMessage = errorObj.getString("message");
                    log.error("OpenAI Response 流式 API 报错: {}", errorMessage);
                    throw new RuntimeException("OpenAI Response 流式 API 报错: " + errorMessage);
                }

                String type = json.getString("type");
                if (!StringUtils.hasText(type)) {
                    type = eventName;
                }
                if (type == null) type = "";

                // 1. 正文 Delta
                if ("response.text.delta".equalsIgnoreCase(type) || "response.output_text.delta".equalsIgnoreCase(type)) {
                    String delta = json.getString("delta");
                    if (!StringUtils.hasText(delta)) {
                        delta = json.getString("text");
                    }
                    if (StringUtils.hasText(delta)) {
                        return CuteChatResponse.builder().content(delta).build();
                    }
                }

                // 2. 思考过程 Delta
                if ("response.reasoning.delta".equalsIgnoreCase(type) || "response.reasoning_text.delta".equalsIgnoreCase(type)) {
                    String delta = json.getString("delta");
                    if (!StringUtils.hasText(delta)) {
                        delta = json.getString("text");
                    }
                    if (StringUtils.hasText(delta)) {
                        return CuteChatResponse.builder().reasoningContent(delta).build();
                    }
                }

                // 3. 工具调用新增 item
                if ("response.output_item.added".equalsIgnoreCase(type)) {
                    Integer outputIndex = json.getInteger("output_index");
                    JSONObject item = json.getJSONObject("item");
                    if (item != null && "function_call".equalsIgnoreCase(item.getString("type"))) {
                        String callId = item.getString("call_id");
                        String id = item.getString("id");
                        String name = item.getString("name");
                        ToolCallBuffer buf = getOrCreateBuffer(outputIndex, callId, null, id);
                        if (StringUtils.hasText(name)) {
                            buf.name = name;
                        }
                        String args = item.getString("arguments");
                        if (StringUtils.hasText(args) && buf.arguments.isEmpty()) {
                            buf.arguments.append(args);
                        }
                    }
                }

                // 4. 工具参数 Delta
                if ("response.function_call_arguments.delta".equalsIgnoreCase(type)) {
                    Integer outputIndex = json.getInteger("output_index");
                    String callId = json.getString("call_id");
                    String itemId = json.getString("item_id");
                    ToolCallBuffer buf = getOrCreateBuffer(outputIndex, callId, itemId, null);
                    String delta = json.getString("delta");
                    if (delta != null) {
                        buf.arguments.append(delta);
                    }
                }

                // 5. 工具参数传输完成
                if ("response.function_call_arguments.done".equalsIgnoreCase(type)) {
                    Integer outputIndex = json.getInteger("output_index");
                    String callId = json.getString("call_id");
                    String itemId = json.getString("item_id");
                    ToolCallBuffer buf = getOrCreateBuffer(outputIndex, callId, itemId, null);
                    String fullArgs = json.getString("arguments");
                    if (StringUtils.hasText(fullArgs) && buf.arguments.isEmpty()) {
                        buf.arguments.append(fullArgs);
                    }
                }

                // 6. 输出项全部完成（全量兜底）
                if ("response.output_item.done".equalsIgnoreCase(type)) {
                    Integer outputIndex = json.getInteger("output_index");
                    JSONObject item = json.getJSONObject("item");
                    if (item != null && "function_call".equalsIgnoreCase(item.getString("type"))) {
                        String callId = item.getString("call_id");
                        String id = item.getString("id");
                        String name = item.getString("name");
                        String args = item.getString("arguments");
                        ToolCallBuffer buf = getOrCreateBuffer(outputIndex, callId, null, id);
                        if (StringUtils.hasText(name)) {
                            buf.name = name;
                        }
                        if (StringUtils.hasText(args) && buf.arguments.isEmpty()) {
                            buf.arguments.append(args);
                        }
                    }
                }

                // 7. 完成事件 / Usage 提取
                if ("response.completed".equalsIgnoreCase(type) || "response.done".equalsIgnoreCase(type)) {
                    JSONObject responseObj = json.getJSONObject("response");
                    JSONObject usageObj = responseObj != null ? responseObj.getJSONObject("usage") : json.getJSONObject("usage");
                    if (usageObj != null) {
                        pendingUsage = parseUsage(usageObj);
                    }
                }

            } catch (Exception e) {
                log.error("解析 OpenAI Response SSE 帧失败: data={}, error={}", data, e.getMessage(), e);
            }
            return null;
        }

        private ToolCallBuffer getOrCreateBuffer(Integer outputIndex, String callId, String itemId, String id) {
            ToolCallBuffer buf = null;
            if (outputIndex != null) {
                buf = bufferByOutputIndex.get(outputIndex);
            }
            if (buf == null && StringUtils.hasText(callId)) {
                buf = bufferById.get(callId);
            }
            if (buf == null && StringUtils.hasText(itemId)) {
                buf = bufferById.get(itemId);
            }
            if (buf == null && StringUtils.hasText(id)) {
                buf = bufferById.get(id);
            }
            if (buf == null) {
                buf = new ToolCallBuffer();
                toolCallList.add(buf);
            }

            // 确定标识符，优先使用 call_id
            String effectiveId = StringUtils.hasText(callId) ? callId : (StringUtils.hasText(id) ? id : itemId);
            if (StringUtils.hasText(effectiveId)) {
                if (!StringUtils.hasText(buf.id) || (effectiveId.startsWith("call_") && !buf.id.startsWith("call_"))) {
                    buf.id = effectiveId;
                }
            } else if (!StringUtils.hasText(buf.id)) {
                buf.id = "call_" + toolCallList.size();
            }

            // 绑定所有关联键
            if (outputIndex != null) {
                bufferByOutputIndex.put(outputIndex, buf);
            }
            if (StringUtils.hasText(callId)) {
                bufferById.put(callId, buf);
            }
            if (StringUtils.hasText(itemId)) {
                bufferById.put(itemId, buf);
            }
            if (StringUtils.hasText(id)) {
                bufferById.put(id, buf);
            }
            return buf;
        }
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private static CuteUsage parseUsage(JSONObject usageObj) {
        if (usageObj == null) return null;
        long cachedTokens = 0L;
        JSONObject inputDetails = usageObj.getJSONObject("input_tokens_details");
        if (inputDetails == null) {
            inputDetails = usageObj.getJSONObject("input_token_details");
        }
        if (inputDetails == null) {
            inputDetails = usageObj.getJSONObject("prompt_tokens_details");
        }
        if (inputDetails != null) {
            cachedTokens = inputDetails.getLongValue("cached_tokens", 0L);
        }

        long inputTokens = usageObj.getLongValue("input_tokens", 0L);
        if (inputTokens == 0L) {
            inputTokens = usageObj.getLongValue("prompt_tokens", 0L);
        }

        long outputTokens = usageObj.getLongValue("output_tokens", 0L);
        if (outputTokens == 0L) {
            outputTokens = usageObj.getLongValue("completion_tokens", 0L);
        }

        return CuteUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cachedTokens(cachedTokens)
                .build();
    }
}
