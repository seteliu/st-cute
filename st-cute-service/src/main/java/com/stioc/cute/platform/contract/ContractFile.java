package com.stioc.cute.platform.contract;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 统一管理 st-cute 的附属配置文件读写契约入口
 */
public class ContractFile {
    
    public static final String FILE_AGENTS = "AGENTS.md";
    public static final String FILE_HOOKS = "hooks.json";
    public static final String FILE_PERMISSION = "permission.json";
    public static final String FILE_PERMISSION_LOCAL = "permission_local.json";
    public static final String FILE_MCP_SERVERS = "mcp_servers.json";
    public static final String DIR_SKILLS = "skills";

    // ==========================================
    // 目录管理
    // ==========================================

    /**
     * 获取全局 st-cute 目录：~/.st-cute
     */
    public static File getGlobalDir() {
        return new File(System.getProperty("user.home"), ".st-cute");
    }

    /**
     * 获取项目级首选配置目录（写入或默认时使用）
     */
    public static File getProjectDir(String projectBasePath) {
        if (projectBasePath == null || projectBasePath.trim().isEmpty()) {
            return null;
        }
        return getProjectDir(new File(projectBasePath));
    }

    /**
     * 获取项目级首选配置目录（写入或默认时使用）：优先支持 .agents 目录，否则使用默认 Rar .st-cute
     */
    public static File getProjectDir(File projectBaseDir) {
        if (projectBaseDir == null) {
            return null;
        }
        File agentsDir = new File(projectBaseDir, ".agents");
        if (agentsDir.exists() && agentsDir.isDirectory()) {
            return agentsDir;
        }
        return new File(projectBaseDir, ".st-cute");
    }

    /**
     * 获取所有项目级配置目录（共存读取时使用）：同时包括存在的 .agents 和 .st-cute 目录
     */
    public static List<File> getProjectDirs(String projectBasePath) {
        if (projectBasePath == null || projectBasePath.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return getProjectDirs(new File(projectBasePath));
    }

    /**
     * 获取所有项目级配置目录（共存读取时使用）：同时包括存在的 .agents 和 .st-cute 目录
     */
    public static List<File> getProjectDirs(File projectBaseDir) {
        List<File> list = new ArrayList<>();
        if (projectBaseDir == null) {
            return list;
        }
        File agentsDir = new File(projectBaseDir, ".agents");
        if (agentsDir.exists() && agentsDir.isDirectory()) {
            list.add(agentsDir);
        }
        File cuteDir = new File(projectBaseDir, ".st-cute");
        if (cuteDir.exists() && cuteDir.isDirectory()) {
            list.add(cuteDir);
        }
        // 如果都不存在，则返回默认的 .st-cute 作为兜底
        if (list.isEmpty()) {
            list.add(cuteDir);
        }
        return list;
    }

    /**
     * 遍历指定项目下所有有效共存配置目录中的特定规约文件，并交由 consumer 处理。
     * 自动过滤不存在的物理文件，外部 Service 只需要关注业务解析逻辑。
     *
     * @param projectBasePath 项目物理根路径
     * @param fileName 规约文件名或目录名 (如 "AGENTS.md", "hooks.json" 等)
     * @param fileConsumer 处理回调函数
     */
    public static void forEachProjectFile(String projectBasePath, String fileName, Consumer<File> fileConsumer) {
        if (projectBasePath == null || projectBasePath.trim().isEmpty() || fileConsumer == null) {
            return;
        }
        for (File dir : getProjectDirs(projectBasePath)) {
            File target = new File(dir, fileName);
            if (target.exists()) {
                fileConsumer.accept(target);
            }
        }
    }

    // ==========================================
    // 全局与项目级配置文件
    // ==========================================

    /**
     * 全局开发指令与规范：~/.st-cute/AGENTS.md
     */
    public static File getGlobalAgentsFile() {
        return new File(getGlobalDir(), "AGENTS.md");
    }

    /**
     * 项目级开发指令与规范：{projectBasePath}/.st-cute/AGENTS.md
     */
    public static File getProjectAgentsFile(String projectBasePath) {
        File dir = getProjectDir(projectBasePath);
        return dir != null ? new File(dir, "AGENTS.md") : null;
    }

    /**
     * 全局外部工具服务配置：~/.st-cute/mcp_servers.json
     */
    public static File getGlobalMcpServersFile() {
        return new File(getGlobalDir(), "mcp_servers.json");
    }

    /**
     * 项目级外部工具服务配置：{projectBasePath}/.st-cute/mcp_servers.json
     */
    public static File getProjectMcpServersFile(String projectBasePath) {
        File dir = getProjectDir(projectBasePath);
        return dir != null ? new File(dir, "mcp_servers.json") : null;
    }

    /**
     * 全局可插拔技能包目录：~/.st-cute/skills
     */
    public static File getGlobalSkillsDir() {
        return new File(getGlobalDir(), "skills");
    }

    /**
     * 项目级可插拔技能包目录：{projectBasePath}/.st-cute/skills
     */
    public static File getProjectSkillsDir(String projectBasePath) {
        File dir = getProjectDir(projectBasePath);
        return dir != null ? new File(dir, "skills") : null;
    }

    /**
     * 全局生命周期切面挂钩：~/.st-cute/hooks.json
     */
    public static File getGlobalHooksFile() {
        return new File(getGlobalDir(), "hooks.json");
    }

    /**
     * 项目级生命周期切面挂钩：{projectBasePath}/.st-cute/hooks.json
     */
    public static File getProjectHooksFile(String projectBasePath) {
        File dir = getProjectDir(projectBasePath);
        return dir != null ? new File(dir, "hooks.json") : null;
    }

    /**
     * 全局权限安全规则：~/.st-cute/permission.json
     */
    public static File getGlobalPermissionFile() {
        return new File(getGlobalDir(), "permission.json");
    }

    /**
     * 项目级权限安全规则：{projectBasePath}/.st-cute/permission.json
     */
    public static File getProjectPermissionFile(String projectBasePath) {
        File dir = getProjectDir(projectBasePath);
        return dir != null ? new File(dir, "permission.json") : null;
    }

    // ==========================================
    // 系统自动生成并自动使用的文件
    // ==========================================

    /**
     * 本地级加白权限规则：{projectBasePath}/{dir}/permission_local.json
     * 遵循以下输出优先级：
     * 1. 如果 .agents 和 .st-cute 同时存在，往 .st-cute 目录输出
     * 2. 如果只有 .agents 存在，往 .agents 目录输出
     * 3. 如果 .agents 不存在，往 .st-cute 目录输出
     */
    public static File getProjectPermissionLocalFile(String projectBasePath) {
        if (projectBasePath == null || projectBasePath.trim().isEmpty()) {
            return null;
        }
        File projectBaseDir = new File(projectBasePath);
        File agentsDir = new File(projectBaseDir, ".agents");
        File cuteDir = new File(projectBaseDir, ".st-cute");

        boolean agentsExists = agentsDir.exists() && agentsDir.isDirectory();
        boolean cuteExists = cuteDir.exists() && cuteDir.isDirectory();

        File targetDir;
        if (agentsExists && cuteExists) {
            targetDir = cuteDir;
        } else if (agentsExists) {
            targetDir = agentsDir;
        } else {
            targetDir = cuteDir;
        }

        return new File(targetDir, FILE_PERMISSION_LOCAL);
    }



    /**
     * 核心 SQLite 数据库：~/.st-cute/st-cute.db
     */
    public static File getGlobalDbFile() {
        return new File(getGlobalDir(), "st-cute.db");
    }

    // ==========================================
    // 供应商配置文件
    // ==========================================

    /**
     * 全局大模型供应商配置文件（JSON）：~/.st-cute/config.json
     */
    public static File getGlobalConfigJsonFile() {
        return new File(getGlobalDir(), "config.json");
    }

    // ==========================================
    // 日志文件
    // ==========================================

    /**
     * 全局级 HTTP 交互载荷日志文件：~/.st-cute/logs/http-log.log
     */
    public static File getGlobalHttpLogFile() {
        return new File(getGlobalDir(), "logs/http-log.log");
    }
}
