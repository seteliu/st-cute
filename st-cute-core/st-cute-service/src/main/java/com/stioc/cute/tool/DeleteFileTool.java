package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.engine.loop.core.AgentContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 删除本地文件或空目录的本地核心修改工具。
 * <p>
 * 能力边界刻意收敛（摩擦层设计）：仅支持删除单个文件或空目录，
 * 目录级递归删除不在本工具职责内（继续走 execute_command 并接受命令黑名单/审批摩擦），
 * 防止工具化让"一键清场"变得过于顺手。
 * </p>
 */
@Slf4j
@Component
public class DeleteFileTool implements CuteTool {

    @Resource
    private ProjectService projectService;

    @Override
    public String getRawName() {
        return ToolNames.DELETE_FILE;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】删除指定文件或空目录。仅支持删除单个文件或空目录，递归删除整个目录树不在本工具能力内（请使用 execute_command 并自行评估风险）。"
                + "删除前必须先用 read_file 或 list_dir 确认目标存在且确属待删除对象，禁止盲目删除。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "待删除的目标路径，支持绝对路径或项目相对路径。仅允许单个文件或空目录"
            }
          },
          "required": ["path"]
        }
        """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 敏感级：删除属高危操作，智能审批模式同样需要人工确认
        return ToolAccessLevel.SENSITIVE;
    }

    @Override
    public String getTargetResource(Map<String, Object> arguments) {
        // ToolArgs 宽松访问：规避 String.valueOf(null) 产生字面量 "null" 路径的边界坑
        return ToolArgs.of(arguments).getStringTrimmed("path");
    }

    @Override
    public String getLockKey(Map<String, Object> arguments) {
        String path = getTargetResource(arguments);
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return "file:" + new File(path).getCanonicalPath();
        } catch (Exception e) {
            return "file:" + path;
        }
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        ToolArgs args = ToolArgs.of(arguments);
        String pathVal = args.getString("path");
        if (pathVal == null || pathVal.isBlank()) {
            return ToolResult.error("参数 'path' 不能为空。");
        }

        try {
            File file = projectService.resolvePath(pathVal, agentContext).toFile();

            if (!file.exists()) {
                return ToolResult.error("目标不存在: " + pathVal);
            }

            if (file.isDirectory()) {
                return deleteEmptyDirectory(file.toPath(), pathVal);
            }
            return deleteFile(file, pathVal);
        } catch (IOException e) {
            log.error("DeleteFileTool 删除异常: {}", pathVal, e);
            return ToolResult.error("删除失败: " + e.getMessage());
        }
    }

    /**
     * 删除单个文件
     */
    private String deleteFile(File file, String pathVal) throws IOException {
        if (!file.isFile()) {
            return ToolResult.error("目标既不是常规文件也不是目录: " + pathVal);
        }
        try {
            boolean removed = Files.deleteIfExists(file.toPath());
            if (!removed) {
                return ToolResult.error("文件在删除前已消失（可能被并发操作移除）: " + pathVal);
            }
        } catch (DirectoryNotEmptyException e) {
            // 理论上 isDirectory 分支先行处理，防御性保留
            return ToolResult.error("目标是一个非空目录，delete_file 仅支持空目录删除。请改用 execute_command 执行目录树删除。");
        } catch (AccessDeniedException e) {
            return ToolResult.error("无权限删除该文件（可能被其他进程占用或系统保护）: " + pathVal);
        }

        log.info("DeleteFileTool 删除文件成功: {}", pathVal);
        return new JSONObject()
                .fluentPut("success", true)
                .fluentPut("message", "已删除文件: " + pathVal)
                .toJSONString();
    }

    /**
     * 删除空目录：非空目录拒绝并给出指引（摩擦层，防一键清场）
     */
    private String deleteEmptyDirectory(Path dirPath, String pathVal) throws IOException {
        // 目录快照非空即拒绝：EMPTY 不创建目录，安全
        try (var stream = Files.list(dirPath)) {
            if (stream.findAny().isPresent()) {
                return ToolResult.error("目录非空，delete_file 仅支持删除空目录。递归删除请使用 execute_command 并自行评估风险: " + pathVal);
            }
        }
        Files.delete(dirPath);
        log.info("DeleteFileTool 删除空目录成功: {}", pathVal);
        return new JSONObject()
                .fluentPut("success", true)
                .fluentPut("message", "已删除空目录: " + pathVal)
                .toJSONString();
    }
}
