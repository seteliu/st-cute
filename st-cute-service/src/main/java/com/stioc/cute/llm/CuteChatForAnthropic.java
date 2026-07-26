package com.stioc.cute.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

/**
 * 基于原生 OkHttp 实现的 Anthropic Claude 协议大模型客户端。
 *
 * <p>Anthropic SSE 事件类型：
 * <ul>
 *   <li>message_start       – 携带初始 usage（input_tokens）</li>
 *   <li>content_block_start – 标记内容块开始（text / tool_use / thinking）</li>
 *   <li>content_block_delta – text_delta / input_json_delta / thinking_delta</li>
 *   <li>content_block_stop  – 内容块结束</li>
 *   <li>message_delta       – 携带最终输出 token 数</li>
 *   <li>message_stop        – 流完全结束</li>
 * </ul>
 *
 * <p><b>消息格式约束：</b>同一轮的所有 tool_result 必须合并到同一个
 * {@code role=user} 消息的 {@code content} 数组中。
 */
@Slf4j
public class CuteChatForAnthropic extends AbstractCuteChat {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    /**
     * 构造 Anthropic 协议客户端实例
     */
    public CuteChatForAnthropic(String baseUrl, String apiKey, String modelName, Double temperature,
                                okhttp3.Interceptor loggingInterceptor) {
        super(baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL,
                apiKey, modelName, temperature, loggingInterceptor);
    }

    // ──────────────────────────────────────────────
    // 协议实现
    // ──────────────────────────────────────────────

    @Override
    protected String protocolName() {
        return "Anthropic";
    }

    @Override
    protected Iterator<CuteChatResponse> createSseIterator(BufferedReader reader) {
        return new AnthropicSseIterator(reader);
    }

    // ──────────────────────────────────────────────
    // SSE Iterator（懒初始化，无副作用构造）
    // ──────────────────────────────────────────────

    private static class AnthropicSseIterator implements Iterator<CuteChatResponse> {

        private final BufferedReader reader;

        /**
         * key = content block 的绝对 index（Anthropic 协议里的 index 字段）
         * value = 该 block 对应的 ToolCallBuffer（仅 tool_use 类型的 block 存入）
         */
        private final Map<Integer, ToolCallBuffer> toolCallByBlockIndex = new LinkedHashMap<>();
        private long inputTokens = 0L;
        private long cachedTokens = 0L;
        private CuteUsage finalUsage = null;

        /** 以 block index 为键，记录每个 content_block 的类型（text / tool_use / thinking） */
        private final Map<Integer, String> blockTypeByIndex = new HashMap<>();
        private String pendingEventType = null;

        private CuteChatResponse nextItem = null;
        private boolean streamDone = false;
        private boolean summaryEmitted = false;

        AnthropicSseIterator(BufferedReader reader) {
            this.reader = reader;
        }

        /**
         * 幂等：如果 nextItem 已经准备好，直接返回 true；否则尝试预取一个元素。
         * 保证多次连续调用 hasNext() 不会丢失数据，符合 Iterator 规范。
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
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        pendingEventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) continue;

                        String eventType = pendingEventType;
                        pendingEventType = null;

                        if ("message_stop".equals(eventType)) {
                            streamDone = true;
                            emitSummaryIfNeeded();
                            return;
                        }

                        try {
                            CuteChatResponse chunk = processEvent(eventType, JSON.parseObject(data));
                            if (chunk != null) {
                                nextItem = chunk;
                                return;
                            }
                        } catch (Exception e) {
                            log.error("解析 Anthropic SSE 帧失败，跳过: event={}, data={}, error={}", 
                                    eventType, data, e.getMessage(), e);
                        }
                    }
                }
                streamDone = true;
                emitSummaryIfNeeded();
            } catch (IOException e) {
                streamDone = true;
                log.error("读取 Anthropic SSE 流失败", e);
                throw new RuntimeException("读取 Anthropic SSE 流失败", e);
            }
        }

        /**
         * 流结束后发出汇总帧。
         * <p>处理逻辑：
         * <ul>
         *   <li>如果存在有效的 tool_use 缓冲区（id 和 name 非空），将 tool calls 和 usage 合并为一个汇总帧发出；</li>
         *   <li>如果 toolCallByBlockIndex 不为空但所有 buffer 都被跳过（id 和 name 均为空，说明不是真正的
         *       tool_use block），则不会产生 tool calls 帧，fallback 到仅发出 usage 帧；</li>
         *   <li>如果既没有 tool calls 也没有 usage，则不发出任何帧。</li>
         * </ul>
         */
        private void emitSummaryIfNeeded() {
            if (summaryEmitted) return;
            summaryEmitted = true;

            if (!toolCallByBlockIndex.isEmpty()) {
                List<CuteToolCall> toolCalls = new ArrayList<>();
                for (ToolCallBuffer buf : toolCallByBlockIndex.values()) {
                    // 跳过未正确初始化的占位（id 和 name 均为空说明不是真正的 tool_use block）
                    if (buf.id.isEmpty() && buf.name.isEmpty()) continue;
                    toolCalls.add(CuteToolCall.builder()
                            .id(buf.id).name(buf.name).arguments(buf.arguments.toString())
                            .build());
                }
                if (!toolCalls.isEmpty()) {
                    nextItem = CuteChatResponse.builder()
                            .toolCalls(toolCalls)
                            .usage(finalUsage)
                            .build();
                    return;
                }
            }
            if (finalUsage != null) {
                nextItem = CuteChatResponse.builder().usage(finalUsage).build();
            }
        }

        private CuteChatResponse processEvent(String eventType, JSONObject json) {
            if (eventType == null) return null;

            return switch (eventType) {
                case "message_start" -> {
                    JSONObject message = json.getJSONObject("message");
                    if (message != null) {
                        JSONObject usage = message.getJSONObject("usage");
                        if (usage != null) {
                            inputTokens = usage.getLongValue("input_tokens", 0L);
                            cachedTokens = usage.getLongValue("cache_read_input_tokens", 0L)
                                    + usage.getLongValue("cache_creation_input_tokens", 0L);
                        }
                    }
                    yield null;
                }
                case "content_block_start" -> {
                    int blockIndex = json.getIntValue("index", 0);
                    JSONObject block = json.getJSONObject("content_block");
                    if (block != null) {
                        String blockType = block.getString("type");
                        blockTypeByIndex.put(blockIndex, blockType);
                        // 只为 tool_use block 创建缓冲区，避免 text/thinking block 产生空占位
                        if ("tool_use".equals(blockType)) {
                            ToolCallBuffer buf = new ToolCallBuffer();
                            buf.id = block.getString("id") != null ? block.getString("id") : "";
                            buf.name = block.getString("name") != null ? block.getString("name") : "";
                            toolCallByBlockIndex.put(blockIndex, buf);
                        }
                    }
                    yield null;
                }
                case "content_block_delta" -> {
                    int index = json.getIntValue("index", 0);
                    JSONObject delta = json.getJSONObject("delta");
                    if (delta == null) yield null;
                    String deltaType = delta.getString("type");
                    yield switch (deltaType != null ? deltaType : "") {
                        case "text_delta" -> {
                            String text = delta.getString("text");
                            if (text == null || text.isEmpty()) yield null;
                            yield CuteChatResponse.builder().content(text).build();
                        }
                        case "thinking_delta" -> {
                            String thinking = delta.getString("thinking");
                            if (thinking == null || thinking.isEmpty()) yield null;
                            yield CuteChatResponse.builder().reasoningContent(thinking).build();
                        }
                        case "input_json_delta" -> {
                            String partial = delta.getString("partial_json");
                            if (partial != null && !partial.isEmpty()) {
                                ToolCallBuffer buf = toolCallByBlockIndex.get(index);
                                if (buf != null) {
                                    buf.arguments.append(partial);
                                }
                            }
                            yield null;
                        }
                        default -> null;
                    };
                }
                case "content_block_stop" -> null;
                case "message_delta" -> {
                    JSONObject usage = json.getJSONObject("usage");
                    if (usage != null) {
                        finalUsage = CuteUsage.builder()
                                .inputTokens(inputTokens)
                                .outputTokens(usage.getLongValue("output_tokens", 0L))
                                .cachedTokens(cachedTokens)
                                .build();
                    }
                    yield null;
                }
                case "error" -> {
                    JSONObject errorDetail = json.getJSONObject("error");
                    String errorType = errorDetail != null ? errorDetail.getString("type") : "unknown";
                    String errorMessage = errorDetail != null ? errorDetail.getString("message") : "unknown error";
                    log.error("Anthropic 流式 API 调用过程中返回错误: type={}, message={}", errorType, errorMessage);
                    throw new RuntimeException("Anthropic 流式 API 报错: " + errorType + " - " + errorMessage);
                }
                default -> null;
            };
        }
    }

    // ──────────────────────────────────────────────
    // 请求构建
    // ──────────────────────────────────────────────

    @Override
    protected String buildRequestBody(CutePrompt prompt, boolean stream) {
        JSONObject body = new JSONObject();
        body.put("model", resolveModel(prompt));
        body.put("max_tokens", resolveMaxTokens(prompt));
        body.put("stream", stream);

        // Anthropic 部分模型（如开启 extended thinking 的 Claude）不支持 temperature，
        // 仅在显式设置时传递
        Double temp = resolveTemperature(prompt);
        if (temp != null) {
            body.put("temperature", temp);
        }

        if (prompt.getOptions() != null && prompt.getOptions().getReasoningEffort() != null && !prompt.getOptions().getReasoningEffort().isBlank()) {
            body.put("reasoning_effort", prompt.getOptions().getReasoningEffort().trim());
        }

        String systemContent = extractSystemPrompt(prompt.getMessages());
        if (systemContent != null && !systemContent.isEmpty()) {
            body.put("system", systemContent);
        }

        body.put("messages", buildAnthropicMessages(prompt.getMessages()));

        if (prompt.getOptions() != null
                && prompt.getOptions().getTools() != null
                && !prompt.getOptions().getTools().isEmpty()) {
            JSONArray tools = new JSONArray();
            for (CuteToolDefinition tool : prompt.getOptions().getTools()) {
                JSONObject toolObj = new JSONObject();
                toolObj.put("name", tool.getName());
                toolObj.put("description", tool.getDescription() != null ? tool.getDescription() : "");
                String schema = tool.getInputSchema();
                if (schema == null || schema.isBlank() || "{}".equals(schema.trim())) {
                    schema = "{\"type\":\"object\",\"properties\":{}}";
                }
                toolObj.put("input_schema", JSON.parseObject(schema));
                tools.add(toolObj);
            }
            body.put("tools", tools);
        }

        return JSON.toJSONString(body, JSONWriter.Feature.WriteNulls);
    }

    /**
     * 将消息列表转为 Anthropic messages 数组。
     * 连续的 TOOL 消息合并进同一个 user 消息的 content 数组。
     * 连续的 USER 消息通过换行符拼接。
     * 连续的 ASSISTANT 消息进行合并聚拢，以满足 Anthropic 角色严格交替的规范。
     * 若规整后的第一条消息不是 user 角色，会自动补充一条空的 user 消息。
     */
    private JSONArray buildAnthropicMessages(List<CuteMessage> messages) {
        // 1. 过滤掉所有的 SYSTEM 消息，保留非空的消息
        List<CuteMessage> filtered = new ArrayList<>();
        for (CuteMessage msg : messages) {
            if (msg.getRole() != CuteMessageRole.SYSTEM) {
                filtered.add(msg);
            }
        }

        if (filtered.isEmpty()) {
            return new JSONArray();
        }

        // 2. 合并连续相同角色的消息，处理严格交替
        JSONArray result = new JSONArray();
        int i = 0;
        while (i < filtered.size()) {
            CuteMessage msg = filtered.get(i);

            if (msg.getRole() == CuteMessageRole.TOOL) {
                // 合并连续的 TOOL 消息到同一个 user 消息的 content 数组中
                JSONArray contentArr = new JSONArray();
                while (i < filtered.size() && filtered.get(i).getRole() == CuteMessageRole.TOOL) {
                    CuteMessage toolMsg = filtered.get(i);
                    JSONObject toolResult = new JSONObject();
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", toolMsg.getToolCallId());
                    toolResult.put("content", toolMsg.getContent() != null ? toolMsg.getContent() : "");
                    contentArr.add(toolResult);
                    i++;
                }
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", contentArr);
                result.add(userMsg);
            } 
            else if (msg.getRole() == CuteMessageRole.USER) {
                // 合并连续的 USER 消息
                StringBuilder sb = new StringBuilder();
                while (i < filtered.size() && filtered.get(i).getRole() == CuteMessageRole.USER) {
                    CuteMessage userMsg = filtered.get(i);
                    if (userMsg.getContent() != null && !userMsg.getContent().isEmpty()) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(userMsg.getContent());
                    }
                    i++;
                }
                JSONObject userMsgObj = new JSONObject();
                userMsgObj.put("role", "user");
                userMsgObj.put("content", sb.toString());
                result.add(userMsgObj);
            } 
            else if (msg.getRole() == CuteMessageRole.ASSISTANT) {
                // 合并连续的 ASSISTANT 消息（防范意外产生的连续 Assistant）
                // 我们直接将它们的内容拼接，并将它们的 tool_calls 聚合
                List<CuteToolCall> aggregatedToolCalls = new ArrayList<>();
                StringBuilder assistantContentSb = new StringBuilder();
                
                while (i < filtered.size() && filtered.get(i).getRole() == CuteMessageRole.ASSISTANT) {
                    CuteMessage assistantMsg = filtered.get(i);
                    if (assistantMsg.getContent() != null && !assistantMsg.getContent().isEmpty()) {
                        if (!assistantContentSb.isEmpty()) {
                            assistantContentSb.append("\n");
                        }
                        assistantContentSb.append(assistantMsg.getContent());
                    }
                    if (assistantMsg.getToolCalls() != null) {
                        aggregatedToolCalls.addAll(assistantMsg.getToolCalls());
                    }
                    i++;
                }

                // 使用聚合后的内容构建单个 ASSISTANT 消息
                CuteMessage aggregatedMsg = CuteMessage.builder()
                        .role(CuteMessageRole.ASSISTANT)
                        .content(assistantContentSb.isEmpty() ? null : assistantContentSb.toString())
                        .toolCalls(aggregatedToolCalls.isEmpty() ? null : aggregatedToolCalls)
                        .build();

                JSONObject converted = convertMessage(aggregatedMsg);
                if (converted != null) {
                    result.add(converted);
                }
            } 
            else {
                // 未知角色直接跳过
                i++;
            }
        }

        // 3. 二次遍历，融合所有可能产生的连续 user 消息，确保 role 严格交替
        JSONArray finalResult = new JSONArray();
        int j = 0;
        while (j < result.size()) {
            JSONObject currentMsg = result.getJSONObject(j);
            String role = currentMsg.getString("role");
            
            if ("user".equals(role)) {
                JSONArray mergedContent = new JSONArray();
                while (j < result.size() && "user".equals(result.getJSONObject(j).getString("role"))) {
                    JSONObject userMsg = result.getJSONObject(j);
                    Object contentObj = userMsg.get("content");
                    if (contentObj instanceof JSONArray) {
                        mergedContent.addAll((JSONArray) contentObj);
                    } else if (contentObj instanceof String) {
                        String text = (String) contentObj;
                        if (!text.isEmpty()) {
                            JSONObject textBlock = new JSONObject();
                            textBlock.put("type", "text");
                            textBlock.put("text", text);
                            mergedContent.add(textBlock);
                        }
                    }
                    j++;
                }
                
                JSONObject finalUserMsg = new JSONObject();
                finalUserMsg.put("role", "user");
                if (mergedContent.isEmpty()) {
                    finalUserMsg.put("content", "");
                } else if (mergedContent.size() == 1 && "text".equals(mergedContent.getJSONObject(0).getString("type"))) {
                    finalUserMsg.put("content", mergedContent.getJSONObject(0).getString("text"));
                } else {
                    finalUserMsg.put("content", mergedContent);
                }
                finalResult.add(finalUserMsg);
            } else {
                finalResult.add(currentMsg);
                j++;
            }
        }

        // 4. 校验并修补首条消息必须是 user 角色的限制
        if (!finalResult.isEmpty()) {
            JSONObject firstMsg = finalResult.getJSONObject(0);
            if (!"user".equals(firstMsg.getString("role"))) {
                log.warn("Anthropic 消息列表首条角色为 assistant，前置补充空 user 消息以对齐角色交替");
                JSONObject emptyUser = new JSONObject();
                emptyUser.put("role", "user");
                emptyUser.put("content", "");
                finalResult.add(0, emptyUser);
            }
        }

        return finalResult;
    }

    private String extractSystemPrompt(List<CuteMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (CuteMessage msg : messages) {
            if (msg.getRole() == CuteMessageRole.SYSTEM && msg.getContent() != null) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(msg.getContent());
            }
        }
        return sb.toString();
    }

    /**
     * 将单条 CuteMessage 转换为 Anthropic 协议 JSON 格式。
     * 仅处理 ASSISTANT 角色消息（USER 消息在 {@link #buildAnthropicMessages} 中直接构建）。
     */
    private JSONObject convertMessage(CuteMessage msg) {
        if (msg.getRole() != CuteMessageRole.ASSISTANT) {
            return null;
        }

        JSONObject obj = new JSONObject();
        obj.put("role", "assistant");
        JSONArray contentArr = new JSONArray();
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", msg.getContent());
            contentArr.add(textBlock);
        }
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            for (CuteToolCall tc : msg.getToolCalls()) {
                JSONObject toolUse = new JSONObject();
                toolUse.put("type", "tool_use");
                toolUse.put("id", tc.getId());
                toolUse.put("name", tc.getName());
                Object input;
                try {
                    input = JSON.parseObject(tc.getArguments() != null ? tc.getArguments() : "{}");
                } catch (Exception e) {
                    input = new JSONObject();
                }
                toolUse.put("input", input);
                contentArr.add(toolUse);
            }
        }
        if (contentArr.isEmpty()) {
            // Anthropic 要求 user/assistant 严格交替，跳过会破坏消息序列，
            // 兜底添加一个空文本块以保持消息结构完整
            log.warn("ASSISTANT 消息 content 和 toolCalls 均为空，已添加空文本块兜底");
            JSONObject emptyText = new JSONObject();
            emptyText.put("type", "text");
            emptyText.put("text", "");
            contentArr.add(emptyText);
        }
        obj.put("content", contentArr);
        return obj;
    }

    @Override
    protected Request buildHttpRequest(String bodyJson) {
        return new Request.Builder()
                .url(baseUrl + "/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
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

        // 检查响应中的 error 字段（部分场景下 HTTP 200 但返回错误信息）
        JSONObject errorObj = json.getJSONObject("error");
        if (errorObj != null) {
            String errorType = errorObj.getString("type");
            String errorMessage = errorObj.getString("message");
            log.error("Anthropic API 调用失败: type={}, message={}", errorType, errorMessage);
            throw new RuntimeException("Anthropic API 调用失败: " + errorType + " - " + errorMessage);
        }

        JSONArray contentArr = json.getJSONArray("content");

        StringBuilder textSb = new StringBuilder();
        StringBuilder reasoningSb = new StringBuilder();
        List<CuteToolCall> toolCalls = new ArrayList<>();

        if (contentArr != null) {
            for (int i = 0; i < contentArr.size(); i++) {
                JSONObject block = contentArr.getJSONObject(i);
                String type = block.getString("type");
                if ("text".equals(type)) {
                    String text = block.getString("text");
                    if (text != null) textSb.append(text);
                } else if ("thinking".equals(type)) {
                    String thinking = block.getString("thinking");
                    if (thinking != null) reasoningSb.append(thinking);
                } else if ("tool_use".equals(type)) {
                    JSONObject inputObj = block.getJSONObject("input");
                    String argsJson = inputObj != null ? inputObj.toJSONString() : "{}";
                    toolCalls.add(CuteToolCall.builder()
                            .id(block.getString("id"))
                            .name(block.getString("name"))
                            .arguments(argsJson)
                            .build());
                }
            }
        }

        return CuteChatResponse.builder()
                .content(textSb.isEmpty() ? null : textSb.toString())
                .reasoningContent(reasoningSb.isEmpty() ? null : reasoningSb.toString())
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .usage(parseUsage(json.getJSONObject("usage")))
                .build();
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private static CuteUsage parseUsage(JSONObject usageObj) {
        if (usageObj == null) return null;
        return CuteUsage.builder()
                .inputTokens(usageObj.getLongValue("input_tokens", 0L))
                .outputTokens(usageObj.getLongValue("output_tokens", 0L))
                .cachedTokens(usageObj.getLongValue("cache_read_input_tokens", 0L)
                        + usageObj.getLongValue("cache_creation_input_tokens", 0L))
                .build();
    }
}
