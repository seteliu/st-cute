package com.stioc.cute.agent.access;

import com.alibaba.fastjson2.JSON;
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

    /**
     * 单个日志文件的最大体积（20MB），超出后滚动到下一个序号文件。
     * 软限制：并发写入瞬间可能略微超出，不做精确截断。
     */
    private static final long MAX_LOG_FILE_BYTES = 20L * 1024 * 1024;

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
    // Raw HTTP 日志（由 OkHttpLoggingInterceptor 调用）
    // ──────────────────────────────────────────────

    public void writeRawHttpRequest(String uuid, String url, String method, Map<String, List<String>> headers, String body) {
        if (!isHttpLogEnabled()) return;
        try {
            // 头部行 + 逐字段行格式，条目之间以空行分隔
            String logLine = String.format("【%s】【%s】【request】\n【url】: %s\n【method】: %s\n【headers】: %s\n【body】: %s\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    url,
                    method,
                    JSON.toJSONString(headers),
                    maskSensitives(body));

            writeToTodayLogFile(logLine);
        } catch (Exception e) {
            log.error("写入原始 HTTP 请求日志异常", e);
        }
    }

    public void writeRawHttpResponse(String uuid, String url, int code, Map<String, List<String>> headers, String body, boolean isStream) {
        if (!isHttpLogEnabled()) return;
        try {
            // 头部行 + 逐字段行格式，条目之间以空行分隔
            String logLine = String.format("【%s】【%s】【response】\n【url】: %s\n【status_code】: %s\n【is_stream】: %s\n【headers】: %s\n【body】: %s\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    url,
                    code,
                    isStream,
                    JSON.toJSONString(headers),
                    body);

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
            // 头部行 + 逐字段行格式，条目之间以空行分隔
            String logLine = String.format("【%s】【%s】【response】\n【url】: %s\n【status_code】: %s\n【is_stream】: true\n【headers】: %s\n【body】: %s\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    url,
                    code,
                    JSON.toJSONString(headers),
                    completeStream);

            writeToTodayLogFile(logLine);
        } catch (Exception e) {
            log.error("写入原始 HTTP 流结束日志异常", e);
        }
    }

    public void writeRawHttpError(String uuid, String url, Throwable t) {
        if (!isHttpLogEnabled()) return;
        try {
            // 头部行 + 逐字段行格式，条目之间以空行分隔
            String logLine = String.format("【%s】【%s】【response】\n【url】: %s\n【error】: %s\n\n",
                    LocalDateTime.now().format(DATE_FORMATTER),
                    uuid,
                    url,
                    t != null ? t.getMessage() : "unknown error");

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
            File todayLogFile = resolveTodayLogFile(logLine);
            Files.writeString(todayLogFile.toPath(), logLine, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("追加日志到当天文件异常", e);
        }
    }

    /**
     * 解析当前应写入的日志文件：
     * 当天 base 文件（http_yyyy-MM-dd.log）放不下时，按序号（_2、_3...）向后滚动，
     * 返回第一个「不存在或加入本条内容后不超 20MB」的文件；跨天后自然从新一天的 base 文件重新开始。
     */
    private File resolveTodayLogFile(String logLine) {
        String dateStr = LocalDate.now().toString();
        File logDir = new File(ContractFile.getGlobalDir(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        long nextBytes = logLine.getBytes(StandardCharsets.UTF_8).length;

        File baseFile = new File(logDir, "http_" + dateStr + ".log");
        if (!baseFile.exists() || baseFile.length() + nextBytes <= MAX_LOG_FILE_BYTES) {
            return baseFile;
        }

        // base 文件放不下，从 _2 开始寻找可用的序号文件
        int seq = 2;
        while (true) {
            File seqFile = new File(logDir, "http_" + dateStr + "_" + seq + ".log");
            if (!seqFile.exists() || seqFile.length() + nextBytes <= MAX_LOG_FILE_BYTES) {
                return seqFile;
            }
            seq++;
        }
    }

    private String maskSensitives(String jsonStr) {
        if (jsonStr == null) return "";
        return jsonStr.replaceAll("\"api_?key\"\\s*:\\s*\"[^\"]+\"", "\"api_key\":\"******\"")
                .replaceAll("Bearer\\s+[a-zA-Z0-9_\\-\\.]+", "Bearer ******");
    }
}
