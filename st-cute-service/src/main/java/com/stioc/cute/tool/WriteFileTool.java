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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 新建或覆写本地文件内容物理落地修改工具
 */
@Slf4j
@Component
public class WriteFileTool implements CuteTool {

    @Resource
    private WorkspacePathResolver workspacePathResolver;

    @Override
    public boolean isWriteTool() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.WRITE_TO_FILE;
    }

    @Override
    public String getDescription() {
        return "覆盖写入文件或新建文件。如果文件所在父目录不存在，将自动级联创建父目录。";
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
            "content": {
              "type": "string",
              "description": "需要写入文件的完整文本内容"
            }
          },
          "required": ["path", "content"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String pathVal = (String) arguments.get("path");
        String contentVal = (String) arguments.get("content");

        if (pathVal == null || pathVal.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'path' 不能为空。").toJSONString();
        }
        if (contentVal == null) {
            contentVal = "";
        }

        try {
            File file = workspacePathResolver.resolvePath(pathVal, agentContext).toFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) {
                    return new JSONObject().fluentPut("error", "无法创建父目录: " + parent.getAbsolutePath()).toJSONString();
                }
                log.info("自动创建了目录: {}", parent.getAbsolutePath());
            }

            Files.writeString(file.toPath(), contentVal, StandardCharsets.UTF_8);
            log.info("WriteFileTool 执行成功: {}", pathVal);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "已成功写入文件: " + pathVal)
                    .toJSONString();
        } catch (IOException e) {
            log.error("WriteFileTool 写入异常", e);
            return new JSONObject().fluentPut("error", "写入文件失败: " + e.getMessage()).toJSONString();
        }
    }
}
