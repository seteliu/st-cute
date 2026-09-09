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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * 移动/重命名/复制本地文件或目录的本地核心修改工具。
 * <p>
 * 单工具承载移动与复制两种形态（copyMode 参数区分），支持文件与目录树；
 * 移动 = 复制 + 删除源（目录树跨场景由 {@link Files#move} 自动选择语义）。
 * 目录树操作经 walkFileTree 逐项统计，结果回显清单供模型核验。
 * </p>
 */
@Slf4j
@Component
public class MoveFileTool implements CuteTool {

    @Resource
    private ProjectService projectService;

    @Override
    public String getRawName() {
        return ToolNames.MOVE_FILE;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】移动、重命名或复制文件/目录。默认为移动（源将不存在）；copyMode=true 时为复制（源保留）。"
                + "支持文件与整个目录树。目标已存在时默认报错，overwrite=true 才允许覆盖。"
                + "修改文件前若需确认内容，可先 read_file；目标目录不存在时自动级联创建。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "source": {
              "type": "string",
              "description": "源路径（文件或目录），支持绝对路径或项目相对路径"
            },
            "target": {
              "type": "string",
              "description": "目标路径（文件或目录）。目标父目录不存在时自动级联创建；目标为已存在的目录时，源将移入/复制到该目录内（保持源名称）"
            },
            "copyMode": {
              "type": "boolean",
              "description": "是否为复制模式。true = 复制（源保留）；false 或省略 = 移动（源将被删除）",
              "default": false
            },
            "overwrite": {
              "type": "boolean",
              "description": "目标已存在时是否覆盖。默认 false（目标存在即报错，防止静默覆盖丢失）",
              "default": false
            }
          },
          "required": ["source", "target"]
        }
        """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 写级：文件/目录的移动与复制，随文件写类工具治理（智能审批放行）
        return ToolAccessLevel.WRITE;
    }

    @Override
    public String getTargetResource(Map<String, Object> arguments) {
        // 双路径声明：源与目标拼接为审计资源标识（Hook 匹配、审批规则展示均可见完整语义）。
        // 审计场景保持原始 Map 访问（null/非字符串形态宽松兜底，避免等级评估期崩溃）
        if (arguments == null) {
            return null;
        }
        Object source = arguments.get("source");
        Object target = arguments.get("target");
        if (source == null && target == null) {
            return null;
        }
        return source + " -> " + target;
    }

    @Override
    public String getLockKey(Map<String, Object> arguments) {
        Object source = arguments != null ? arguments.get("source") : null;
        if (source == null) {
            return null;
        }
        try {
            // 锁源路径：同源并发移动/复制互斥；与写工具统一 file: 命名空间
            return "file:" + new File(String.valueOf(source)).getCanonicalPath();
        } catch (Exception e) {
            return "file:" + source;
        }
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        ToolArgs args = ToolArgs.of(arguments);
        String sourceVal = args.getString("source");
        String targetVal = args.getString("target");
        boolean copyMode = Boolean.TRUE.equals(args.getBoolean("copyMode"));
        boolean overwrite = Boolean.TRUE.equals(args.getBoolean("overwrite"));

        if (sourceVal == null || sourceVal.isBlank()) {
            return ToolResult.error("参数 'source' 不能为空。");
        }
        if (targetVal == null || targetVal.isBlank()) {
            return ToolResult.error("参数 'target' 不能为空。");
        }

        try {
            Path source = projectService.resolvePath(sourceVal, agentContext);
            Path target = projectService.resolvePath(targetVal, agentContext);

            if (!Files.exists(source)) {
                return ToolResult.error("源路径不存在: " + sourceVal);
            }
            if (source.equals(target)) {
                return ToolResult.error("源路径与目标路径相同: " + sourceVal);
            }

            // 目标为已存在目录时，语义转为"移入/复制到该目录内"（保持源名称），与 shell mv/cp 直觉一致
            if (Files.isDirectory(target)) {
                target = target.resolve(source.getFileName());
            }

            // 防静默覆盖：目标已存在且未显式允许覆盖时报错，让模型显式决策
            if (Files.exists(target) && !overwrite) {
                return ToolResult.error("目标已存在: " + targetVal + "。如需覆盖请显式传 overwrite=true，或更换目标路径。");
            }

            // 目录树操作先统计规模供结果回显；失败回退为单文件标记
            boolean sourceIsDir = Files.isDirectory(source);
            int fileCount = sourceIsDir ? countFiles(source) : 1;

            // 目标父目录级联创建
            Path targetParent = target.getParent();
            if (targetParent != null && !Files.exists(targetParent)) {
                Files.createDirectories(targetParent);
            }

            long bytes = performTransfer(source, target, copyMode, overwrite);

            String action = copyMode ? "复制" : "移动";
            String summary = String.format("已%s %s -> %s（%s，共 %d 个文件，%s）",
                    action, sourceVal, targetVal, sourceIsDir ? "目录树" : "单文件", fileCount, formatSize(bytes));

            log.info("MoveFileTool {}成功: {} -> {}, 文件数 {}, 字节数 {}", action, sourceVal, targetVal, fileCount, bytes);
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", summary)
                    .fluentPut("source", sourceVal)
                    .fluentPut("target", targetVal)
                    .fluentPut("mode", copyMode ? "copy" : "move")
                    .fluentPut("fileCount", fileCount)
                    .fluentPut("bytes", bytes)
                    .toJSONString();
        } catch (FileAlreadyExistsException e) {
            return ToolResult.error("目标已存在: " + targetVal + "。如需覆盖请显式传 overwrite=true。");
        } catch (DirectoryNotEmptyException e) {
            // 非空目录覆盖场景（Windows 向非空目标目录移动）：REPLACE 仅覆盖同名文件无法清空目录
            return ToolResult.error("目标为非空目录，无法整体覆盖。请更换目标路径或指定具体文件级目标。");
        } catch (AccessDeniedException e) {
            return ToolResult.error("无权限访问源或目标路径（可能被其他进程占用）: " + sourceVal + " -> " + targetVal);
        } catch (IOException e) {
            log.error("MoveFileTool 操作异常: {} -> {}", sourceVal, targetVal, e);
            return ToolResult.error((copyMode ? "复制" : "移动") + "失败: " + e.getMessage());
        }
    }

    /**
     * 执行实际传输：移动走 Files.move（同盘原子重命名，跨盘自动降级 copy+delete），
     * 复制走 walkFileTree 逐项复制并累计字节数
     *
     * @return 传输的总字节数（移动时为源规模估算，复制时为精确累计）
     */
    private long performTransfer(Path source, Path target, boolean copyMode, boolean overwrite) throws IOException {
        StandardCopyOption[] options = overwrite
                ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{};

        if (!copyMode) {
            // 移动：先量源规模供回显（move 后源消失无法再统计）
            long bytes = Files.isDirectory(source) ? countBytes(source) : Files.size(source);
            Files.move(source, target, options);
            return bytes;
        }

        if (Files.isRegularFile(source)) {
            // 单文件复制
            long bytes = Files.size(source);
            Files.copy(source, target, options);
            return bytes;
        }

        // 目录树复制：逐项遍历（目录先建、文件后拷），保证层级正确
        long[] totalBytes = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path destDir = target.resolve(source.relativize(dir).toString());
                Files.createDirectories(destDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destFile = target.resolve(source.relativize(file).toString());
                // 单文件覆盖同样需要显式 REPLACE 选项，与顶层语义一致
                Files.copy(file, destFile, options);
                totalBytes[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return totalBytes[0];
    }

    /**
     * 递归统计目录树内的文件数量（不含目录自身）
     */
    private int countFiles(Path dir) throws IOException {
        int[] count = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    /**
     * 递归统计目录树内的总字节数
     */
    private long countBytes(Path dir) throws IOException {
        long[] total = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    /**
     * 字节数人性化展示
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
