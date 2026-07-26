package com.stioc.cute.agent.access;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.contract.ContractProperty;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 大模型调用底层 HTTP 原始 Payload 物理日志记录服务
 */
@Slf4j
@Service
public class LlmLoggerService {

    @Resource
    private ContractProperty contractProperty;

    /**
     * 日志时间输出格式化格式
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // ──────────────────────────────────────────────
    // 开关查询
    // ──────────────────────────────────────────────

    /**
     * 返回当前是否启用 HTTP 日志记录。
     * 调用方可先判断此开关再决定是否构造日志相关逻辑，避免无谓的对象创建。
     */
    public boolean isHttpLogEnabled() {
        return contractProperty != null
                && contractProperty.getLlmLog() != null
                && contractProperty.getLlmLog().isHttpLog();
    }

    // ──────────────────────────────────────────────
    // 业务层日志
    // ──────────────────────────────────────────────

    /**
     * 记录大模型单次交互的输入与输出 Payload 文本日志
     */
    public void logInteraction(Long cid, String provider, String model, String systemPrompt,
                               String userText, String responseText, String reasoningText) {
        if (!isHttpLogEnabled()) return;
        try {
            JSONObject requestJson = new JSONObject();
            requestJson.put("model", model);
            requestJson.put("system_prompt", systemPrompt);
            requestJson.put("messages", buildUserMessage(userText));

            JSONObject responseJson = new JSONObject();
            responseJson.put("content", responseText);
            if (reasoningText != null) {
                responseJson.put("reasoning_content", reasoningText);
            }

            JSONObject detailJson = new JSONObject();
            detailJson.put("cid", cid);
            detailJson.put("provider", provider);
            detailJson.put("request_payload", JSON.parse(maskSensitives(requestJson.toJSONString())));
            detailJson.put("response_payload", JSON.parse(responseJson.toJSONString()));

            String uuid = java.util.UUID.randomUUID().toString();
            String logLine = String.format("【%s】【%s】【response】【%s】\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    detailJson.toJSONString());

            writeToTodayLogFile(logLine);
        } catch (Exception e) {
            log.error("写入 LLM Payload 调试日志异常: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Raw HTTP 日志（由 OkHttpLoggingInterceptor 调用）
    // ──────────────────────────────────────────────

    public void writeRawHttpRequest(String uuid, String url, String method, Map<String, List<String>> headers, String body) {
        if (!isHttpLogEnabled()) return;
        try {
            JSONObject detailJson = new JSONObject();
            detailJson.put("url", url);
            detailJson.put("method", method);
            detailJson.put("headers", headers);
            detailJson.put("body", parseBodyOrString(maskSensitives(body)));

            String logLine = String.format("【%s】【%s】【request】【%s】\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    detailJson.toJSONString());

            writeToTodayLogFile(logLine);
        } catch (Exception e) {
            log.error("写入原始 HTTP 请求日志异常", e);
        }
    }

    public void writeRawHttpResponse(String uuid, String url, int code, Map<String, List<String>> headers, String body, boolean isStream) {
        if (!isHttpLogEnabled()) return;
        try {
            JSONObject detailJson = new JSONObject();
            detailJson.put("url", url);
            detailJson.put("status_code", code);
            detailJson.put("is_stream", isStream);
            detailJson.put("headers", headers);
            detailJson.put("body", parseBodyOrString(body));

            String logLine = String.format("【%s】【%s】【response】【%s】\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    detailJson.toJSONString());

            writeToTodayLogFile(logLine);
        } catch (Exception e) {
            log.error("写入原始 HTTP 响应日志异常", e);
        }
    }

    /**
     * SSE 流式响应完整 body 日志，在流关闭后由 OkHttpLoggingInterceptor.SpyResponseBody 触发。
     * headers 和 status_code 与流开始时的响应头一致，body 为完整的 SSE 原文（最大 20MB，超出则截断）。
     */
    public void writeRawHttpStreamComplete(String uuid, String url, int code, Map<String, List<String>> headers, String completeStream) {
        if (!isHttpLogEnabled()) return;
        try {
            JSONObject detailJson = new JSONObject();
            detailJson.put("url", url);
            detailJson.put("status_code", code);
            detailJson.put("is_stream", true);
            detailJson.put("headers", headers);
            detailJson.put("body", completeStream);

            String logLine = String.format("【%s】【%s】【response】【%s】\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    detailJson.toJSONString());

            writeToTodayLogFile(logLine);
        } catch (Exception e) {
            log.error("写入原始 HTTP 流结束日志异常", e);
        }
    }

    public void writeRawHttpError(String uuid, String url, Throwable t) {
        if (!isHttpLogEnabled()) return;
        try {
            JSONObject detailJson = new JSONObject();
            detailJson.put("url", url);
            detailJson.put("error", t != null ? t.getMessage() : "unknown error");

            String logLine = String.format("【%s】【%s】【response】【%s】\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    detailJson.toJSONString());

            writeToTodayLogFile(logLine);
        } catch (Exception e) {
            log.error("写入原始 HTTP 异常日志异常", e);
        }
    }

    // ──────────────────────────────────────────────
    // 内部工具方法
    // ──────────────────────────────────────────────

    private void writeToTodayLogFile(String logLine) {
        try {
            String dateStr = LocalDate.now().toString();
            File todayLogFile = new File(ContractFile.getGlobalDir(), "logs/" + dateStr + "-http.log");
            File parent = todayLogFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.writeString(todayLogFile.toPath(), logLine, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("追加日志到当天文件异常", e);
        }
    }

    private Object parseBodyOrString(String body) {
        if (body == null) return "";
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.parse(trimmed);
            } catch (Exception ignored) {
            }
        }
        return body;
    }

    private String maskSensitives(String jsonStr) {
        if (jsonStr == null) return "";
        return jsonStr.replaceAll("\"api_?key\"\\s*:\\s*\"[^\"]+\"", "\"api_key\":\"******\"")
                .replaceAll("Bearer\\s+[a-zA-Z0-9_\\-\\.]+", "Bearer ******");
    }

    private JSONObject buildUserMessage(String text) {
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", text);
        return msg;
    }
}
