package com.stioc.cute.tool.findtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.project.ProjectService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件检索与搜索工具通用抽象基类，收敛项目路径解析、沙箱守卫、结果限制截断与容错日志
 */
@Slf4j
public abstract class AbstractFindTool implements CuteTool {

    @Resource
    protected ProjectService projectService;

    @Resource
    protected ContractProperty contractProperty;

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 只读：无外部副作用，支持并发调度
        return ToolAccessLevel.READ;
    }

    /**
     * 解析搜索根路径：若传入了非空 rootDirVal 则使用 projectService 解析，否则取当前项目根目录
     */
    protected Path resolveSearchRoot(String rootDirVal, AgentContext agentContext) {
        if (rootDirVal != null && !rootDirVal.isBlank()) {
            return projectService.resolvePath(rootDirVal, agentContext);
        }
        return Paths.get(projectService.getProjectBasePath(agentContext)).toAbsolutePath().normalize();
    }

    /**
     * 获取当前项目物理根路径 Path，用于相对路径格式化
     */
    protected Path getProjectRoot(AgentContext agentContext) {
        String basePath = projectService.getProjectBasePath(agentContext);
        if (basePath != null && !basePath.isBlank()) {
            return Paths.get(basePath).toAbsolutePath().normalize();
        }
        return null;
    }

    /**
     * 纵深防御：沙箱二次校验。沙箱关闭时跳过
     *
     * @return 校验未通过时返回错误拒绝文本，通过时返回 null
     */
    protected String checkSandbox(Path targetPath, AgentContext agentContext, String paramName) {
        if (contractProperty == null || contractProperty.isPathSandboxEnabled()) {
            return SearchSandboxGuard.check(targetPath, agentContext, projectService, paramName);
        }
        return null;
    }

    /**
     * 统一计算标准化的相对路径（正斜杠分隔），优先相对于项目根目录，以便下游 read_file/edit_file 工具链直接消费
     */
    protected String formatRelativePath(Path file, Path searchRoot, Path projectRoot) {
        if (projectRoot != null && file.startsWith(projectRoot)) {
            return projectRoot.relativize(file).toString().replace("\\", "/");
        }
        return searchRoot.relativize(file).toString().replace("\\", "/");
    }

    /**
     * 尽力而为容错处理：当单个文件不可访问时记录 debug 日志并继续遍历
     */
    protected FileVisitResult handleVisitFileFailed(Path file, IOException exc) {
        // 尽力而为语义：单个不可访问文件（权限/占用锁定）只跳过不炸整次搜索，
        // 默认实现会直接抛 IOException 终止整棵目录树的遍历
        log.debug("{} 跳过不可访问文件: {}, 原因: {}", getClass().getSimpleName(), file, exc.getMessage());
        return FileVisitResult.CONTINUE;
    }
}
