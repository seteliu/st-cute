package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 获取 st-cute 平台规约文档的内置只读工具。
 * AI 按需调用以获取 skill、rule、mcp、hook 等配置文件的存放位置与格式说明。
 */
@Slf4j
@Component
public class GetDocTool implements CuteTool {

    /**
     * topic 与 classpath 文档路径的映射关系（有序，保证 all 输出顺序稳定）
     */
    private static final Map<String, String> DOC_MAP = new LinkedHashMap<>();

    static {
        DOC_MAP.put("convention", "docs/02_file_conventions.md");
        DOC_MAP.put("rule", "docs/05_RULE.md");
        DOC_MAP.put("skill", "docs/06_SKILL.md");
        DOC_MAP.put("mcp", "docs/07_MCP.md");
        DOC_MAP.put("hook", "docs/08_HOOK.md");
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.GET_DOC;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】获取 st-cute 平台的规约文档（skill、rule、mcp、hook、convention 等配置的存放位置、格式说明与使用约定）。当用户询问各类配置文件应放在哪、格式怎么写时，调用此工具获取权威说明。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "topic": {
              "type": "string",
              "description": "要查询的文档主题，可选值：convention（文件规约总览）、rule（AGENTS.md规则）、skill（技能包）、mcp（外部工具服务）、hook（生命周期钩子）、all（全部）",
              "enum": ["convention", "rule", "skill", "mcp", "hook", "all"]
            }
          },
          "required": ["topic"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String topic = (String) arguments.get("topic");
        if (topic == null || topic.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'topic' 不能为空。可选值：convention, rule, skill, mcp, hook, all").toJSONString();
        }

        topic = topic.toLowerCase().trim();

        if ("all".equals(topic)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : DOC_MAP.entrySet()) {
                String content = readClasspathFile(entry.getValue());
                if (content != null) {
                    sb.append("--- ").append(entry.getKey()).append(" ---\n\n");
                    sb.append(content).append("\n\n");
                }
            }
            if (sb.isEmpty()) {
                return new JSONObject().fluentPut("error", "未能加载任何文档").toJSONString();
            }
            return sb.toString();
        }

        String docPath = DOC_MAP.get(topic);
        if (docPath == null) {
            return new JSONObject().fluentPut("error", "未知的文档主题: " + topic + "。可选值：convention, rule, skill, mcp, hook, all").toJSONString();
        }

        String content = readClasspathFile(docPath);
        if (content == null) {
            return new JSONObject().fluentPut("error", "文档文件不存在或读取失败: " + docPath).toJSONString();
        }

        log.info("GetDocTool 成功返回文档: {}", topic);
        return content;
    }

    /**
     * 从 classpath 读取文本文件内容
     */
    private String readClasspathFile(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取 classpath 文档失败: {}", path, e);
            return null;
        }
    }
}
