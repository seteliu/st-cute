package com.stioc.cute.tool.findtool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.platform.security.DesktopTokenStore;
import com.stioc.cute.project.ProjectService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 搜索类工具（find_files / grep_search）的 rootDir 沙箱纵深防御守卫。
 * <p>
 * 权限层（PermissionService 层级 4）已将 rootDir 纳入参数名安检，本守卫在工具层做二次复核：
 * 即使权限层参数名提取链未来演化出新的旁路，越界路径也无法穿透到遍历/检索阶段，
 * 防止沙箱外任意目录结构与文件内容被读取后回显模型上下文。
 * 判定口径与权限层沙箱保持一致：允许项目根目录、系统临时目录与用户级配置目录（~/.st-cute）。
 * </p>
 */
@Slf4j
public final class SearchSandboxGuard {

    private SearchSandboxGuard() {
        // 工具类禁止实例化
    }

    /**
     * 校验搜索根路径是否落在沙箱允许范围内。
     *
     * @param targetPath     已解析的搜索根绝对路径
     * @param agentContext   当前会话上下文（可能为 null，按无项目绑定兜底）
     * @param projectService 项目服务（提供项目根路径解析）
     * @param paramName      越界提示中回显的参数名（如 "rootDir"）
     * @return 越界时的拒绝 JSON 文案；校验通过返回 null 放行
     */
    public static String check(Path targetPath, AgentContext agentContext, ProjectService projectService, String paramName) {
        if (targetPath == null) {
            return null;
        }
        try {
            String projectBasePath = projectService.getProjectBasePath(agentContext);
            Path projectRoot = Paths.get(projectBasePath != null ? projectBasePath : ".").toAbsolutePath().normalize();
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            Path userHomeConfig = ContractFile.getGlobalDir().toPath().toAbsolutePath().normalize();

            Path realTarget = toRealPathSafe(targetPath);
            Path realProjectRoot = toRealPathSafe(projectRoot);
            Path realTempDir = toRealPathSafe(tempDir);
            Path realUserHome = toRealPathSafe(userHomeConfig);

            // 凭证排除：全局配置目录内的敏感凭证文件不因「落在沙箱根内」而放行，
            // 否则搜索引擎可直接读取 config.json 中的 apiKey 与桌面端停机凭证
            if (realTarget != null && isCredentialPath(realTarget, realUserHome)) {
                log.warn("[搜索沙箱纵深防御] {} 命中敏感凭证路径拦截: {}", paramName, targetPath);
                return ToolResult.error("参数 '" + paramName + "' 指向系统敏感凭证文件，禁止访问: " + targetPath);
            }

            // 真实物理路径判定：软链接展开后的物理落点必须落在沙箱允许根目录之内，杜绝软链接逃逸穿透
            boolean inSandbox = realTarget != null && (
                    realTarget.startsWith(realProjectRoot)
                    || realTarget.startsWith(realTempDir)
                    || realTarget.startsWith(realUserHome));
            if (inSandbox) {
                return null;
            }
            log.warn("[搜索沙箱纵深防御] {} 越界拦截: {}", paramName, targetPath);
            return ToolResult.error("参数 '" + paramName + "' 路径越界。禁止访问项目根目录或临时目录外的系统敏感路径: "
                    + targetPath + "。请将搜索范围限制在项目内。");
        } catch (Exception e) {
            log.error("[搜索沙箱纵深防御] 路径校验异常，按越界拒绝: {}", targetPath, e);
            return ToolResult.error("解析搜索路径失败: " + targetPath + ", 原因: " + e.getMessage());
        }
    }

    /**
     * 判定目标路径是否命中敏感凭证文件。
     * <p>
     * 与 PermissionService 的口径保持一致：仅拦凭证文件本身，
     * 不整目录封禁（全局目录中还有 skills、rules 等可正常检索的资产）。
     * </p>
     *
     * @param realTarget   已展开的真实物理路径
     * @param realUserHome 已展开的用户级配置目录物理路径
     * @return true 表示命中敏感凭证文件
     */
    private static boolean isCredentialPath(Path realTarget, Path realUserHome) {
        if (realUserHome == null) {
            return false;
        }
        Path tokenFile = realUserHome.resolve(DesktopTokenStore.TOKEN_FILE_NAME).normalize();
        if (realTarget.equals(tokenFile)) {
            return true;
        }
        Path globalConfigFile = toRealPathSafe(
                ContractFile.getGlobalConfigJsonFile().toPath().toAbsolutePath().normalize());
        return globalConfigFile != null && realTarget.equals(globalConfigFile);
    }

    /**
     * 将路径转换为真实的物理规范路径（展开软链接与 Windows 8.3 短路径名），消除别名判定失真
     */
    private static Path toRealPathSafe(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (Files.exists(path)) {
                return path.toRealPath();
            }
            return Paths.get(path.toFile().getCanonicalPath());
        } catch (Exception e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
