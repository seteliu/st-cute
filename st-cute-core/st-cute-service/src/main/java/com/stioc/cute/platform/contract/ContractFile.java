package com.stioc.cute.platform.contract;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 统一管理 st-cute 的附属配置文件读写契约入口。
 * <p>
 * 三级配置层级术语（优先级从高到低：项目级 &gt; 项目通用级 &gt; 全局级）：
 * <ul>
 *     <li>全局级：~/.st-cute（用户主目录，所有项目共享）</li>
 *     <li>项目级：{projectBasePath}/.st-cute（项目专有，优先级最高）</li>
 *     <li>项目通用级：{projectBasePath}/.agents（项目共享，适合提交 Git 团队统一）</li>
 * </ul>
 * 叠加型契约（Rule、Hook）：三层全部生效，按 项目级 → 项目通用级 → 全局级 顺序装载注入；
 * 覆盖型契约（Skill、MCP）：按 全局级 → 项目通用级 → 项目级 顺序读取，同名后写覆盖。
 * 例外：Permission 仅认 全局级 + 项目级 + 本地级，完全不读项目通用级。
 * </p>
 */
public class ContractFile {
    
    public static final String FILE_AGENTS = "AGENTS.md";
    public static final String FILE_HOOKS = "hooks.json";
    public static final String FILE_PERMISSION = "permission.json";
    public static final String FILE_PERMISSION_LOCAL = "permission_local.json";
    public static final String FILE_MCP_SERVERS = "mcp_servers.json";
    public static final String DIR_SKILLS = "skills";

    /**
     * 项目级子目录规则集目录名：与 AGENTS.md 同级的 rules 目录，一文件一规则
     */
    public static final String DIR_RULES = "rules";

    /**
     * 全局级配置目录默认名：位于用户主目录下（~/.st-cute）。
     */
    public static final String DEFAULT_GLOBAL_DIR_NAME = ".st-cute";

    /**
     * 全局级配置目录名覆盖属性键。
     * <p>
     * 未设置或为空时使用 {@link #DEFAULT_GLOBAL_DIR_NAME}（生产行为零变化）；
     * 测试期由构建工具传入（如 {@code -Dst-cute.config.dir-name=.st-cute-test}），
     * 即可把全局配置、数据库、附件与临时目录整体隔离到真实用户主目录下的独立目录，
     * 彻底避免测试污染生产数据。本属性由本类单点解析，全工程禁止再另行拼装目录名。
     * </p>
     */
    public static final String PROP_GLOBAL_DIR_NAME = "st-cute.config.dir-name";

    // ==========================================
    // 目录管理
    // ==========================================

    /**
     * 解析全局级配置目录名：优先取系统属性 {@link #PROP_GLOBAL_DIR_NAME}，缺省回退默认名
     */
    public static String resolveGlobalDirName() {
        String override = System.getProperty(PROP_GLOBAL_DIR_NAME);
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return DEFAULT_GLOBAL_DIR_NAME;
    }

    /**
     * 获取全局级配置目录：{用户主目录}/{全局级配置目录名}（默认 ~/.st-cute，所有项目共享）。
     * 目录名为本类单点解析，全局级路径一律经本方法取得，禁止自行拼装
     */
    public static File getGlobalDir() {
        return new File(System.getProperty("user.home"), resolveGlobalDirName());
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
     * 获取项目首选配置目录（写入或默认时使用）：项目通用级 .agents 存在则用之，否则回退项目级 .st-cute
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
     * 获取所有项目配置目录（共存读取时使用）：同时包括存在的项目通用级 .agents 与项目级 .st-cute，
     * 返回顺序为 项目通用级 → 项目级，供覆盖型契约（Skill、MCP）"全局级 → 项目通用级 → 项目级"顺序读取使用
     */
    public static List<File> getProjectDirs(String projectBasePath) {
        if (projectBasePath == null || projectBasePath.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return getProjectDirs(new File(projectBasePath));
    }

    /**
     * 获取所有项目配置目录（共存读取时使用）：同时包括存在的项目通用级 .agents 与项目级 .st-cute，
     * 返回顺序为 项目通用级 → 项目级，供覆盖型契约（Skill、MCP）"全局级 → 项目通用级 → 项目级"顺序读取使用
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
     * 获取项目级配置目录（固定）：始终定位 {projectBasePath}/.st-cute，即使项目通用级 .agents 存在。
     * 供 Permission 等仅认项目级、完全不读项目通用级的契约使用
     */
    public static File getProjectLevelDir(String projectBasePath) {
        if (projectBasePath == null || projectBasePath.trim().isEmpty()) {
            return null;
        }
        return new File(projectBasePath, ".st-cute");
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

    /**
     * 按优先级从高到低遍历项目配置目录下的规约文件：项目级 .st-cute → 项目通用级 .agents。
     * 供 Rule、Hook 等叠加型契约使用，保证高优先级来源先装载、注入在列表/提示词前面；
     * 自动过滤不存在的物理文件，外部 Service 只需要关注业务解析逻辑。
     *
     * @param projectBasePath 项目物理根路径
     * @param fileName 规约文件名或目录名 (如 "AGENTS.md", "rules" 等)
     * @param fileConsumer 处理回调函数
     */
    public static void forEachProjectFileByPriority(String projectBasePath, String fileName, Consumer<File> fileConsumer) {
        if (projectBasePath == null || projectBasePath.trim().isEmpty() || fileConsumer == null) {
            return;
        }
        // 1. 项目级 .st-cute 优先
        File levelFile = new File(new File(projectBasePath, ".st-cute"), fileName);
        if (levelFile.exists()) {
            fileConsumer.accept(levelFile);
        }
        // 2. 项目通用级 .agents 次之
        File commonFile = new File(new File(projectBasePath, ".agents"), fileName);
        if (commonFile.exists()) {
            fileConsumer.accept(commonFile);
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
     * 本地级加白权限规则：{projectBasePath}/.st-cute/permission_local.json。
     * 权限契约仅认 全局级与项目级（项目通用级 .agents 完全不参与），加白固定写入项目级目录
     */
    public static File getProjectPermissionLocalFile(String projectBasePath) {
        File levelDir = getProjectLevelDir(projectBasePath);
        if (levelDir == null) {
            return null;
        }
        return new File(levelDir, FILE_PERMISSION_LOCAL);
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
