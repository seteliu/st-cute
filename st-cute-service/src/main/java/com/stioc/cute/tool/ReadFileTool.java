package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.security.access.WorkspacePathResolver;
import com.stioc.cute.agent.access.AgentContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 安全读取本地物理文件内容的本地核心只读工具
 */
@Slf4j
@Component
public class ReadFileTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.READ_FILE;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】读取指定文件的内容。每行附带行号。修改文件前，你必须先使用此工具阅读其最新内容以防止幻觉，否则修改将被系统拒绝。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "目标文件路径，支持绝对路径或项目相对路径"
            },
            "startLine": {
              "type": "integer",
              "description": "读取的起始行号 (1-indexed)，可选，默认为 1"
            },
            "lineCount": {
              "type": "integer",
              "description": "读取的行数，可选，默认读取 1000 行",
              "default": 1000
            }
          },
          "required": ["path"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String pathVal = (String) arguments.get("path");
        if (pathVal == null || pathVal.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'path' 不能为空。").toJSONString();
        }

        int startLine = 1;
        if (arguments.get("startLine") != null) {
            startLine = ((Number) arguments.get("startLine")).intValue();
        }
        int lineCount = 1000;
        if (arguments.get("lineCount") != null) {
            lineCount = ((Number) arguments.get("lineCount")).intValue();
        }

        File file = workspacePathResolver.resolvePath(pathVal, agentContext).toFile();

        if (!file.exists()) {
            return new JSONObject().fluentPut("error", "文件不存在: " + pathVal).toJSONString();
        }
        if (file.isDirectory()) {
            return new JSONObject().fluentPut("error", "路径是一个目录，无法作为文件读取: " + pathVal).toJSONString();
        }
        if (!file.canRead()) {
            return new JSONObject().fluentPut("error", "无权读取该文件: " + pathVal).toJSONString();
        }

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int currentLine = 0;
            int linesRead = 0;
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine >= startLine) {
                    content.append(currentLine).append(": ").append(line).append("\n");
                    linesRead++;
                    if (linesRead >= lineCount) {
                        content.append("... [此处已截断，已达到读取行数限制 ").append(lineCount).append(" 行] ...");
                        break;
                    }
                }
            }
            log.info("ReadFileTool 执行成功: {}, 读取了 {} 行", pathVal, linesRead);

            if (agentContext != null) {
                agentContext.getReadFiles().add(file.getAbsolutePath());
            }

            return content.toString();
        } catch (IOException e) {
            log.error("ReadFileTool 读取异常", e);
            return new JSONObject().fluentPut("error", "读取文件失败: " + e.getMessage()).toJSONString();
        }
    }
}
