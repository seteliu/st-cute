package com.stioc.cute.tool.filetool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.file.FileHashSupport;
import com.stioc.cute.project.ProjectService;
import com.stioc.cute.runtime.loop.RuntimeContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * 本地文件操作工具通用抽象基类。
 * <p>
 * 职责收敛：
 * <ul>
 *   <li>统一下发 {@link ProjectService} 进行多级相对/绝对路径解析；</li>
 *   <li>统一资源定位符与文件粒度并发锁键生成；</li>
 *   <li>统一内容安全哈希门禁登记与前置校验（防幻觉与过时修改）；</li>
 *   <li>异常文案安全防 NPE 提取。</li>
 * </ul>
 * </p>
 */
@Slf4j
public abstract class AbstractFileTool implements CuteTool {

    /**
     * 当前操作系统是否为 Windows 平台静态缓存，避免每次获取锁键重复查询系统属性
     */
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Resource
    protected ProjectService projectService;

    @Override
    public String getTargetResource(Map<String, Object> arguments, AgentContext context) {
        // ToolArgs 宽松访问：规避 String.valueOf(null) 产生字面量 "null" 路径的边界坑
        return ToolArgs.of(arguments).getStringTrimmed("path");
    }

    @Override
    public String getLockKey(Map<String, Object> arguments, AgentContext context) {
        String path = getTargetResource(arguments, context);
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            // 与 execute 统一走 ProjectService 解析：相对路径以项目根为基准，
            // 确保相对与绝对两种传参形态得到相同的规范化锁键，使条带互斥锁稳定生效；
            // Windows 下统一小写化，消除文件尚未创建时 getCanonicalPath 保留调用方大小写导致的互斥失效
            String canonical = projectService.resolvePath(path, context).toFile().getCanonicalPath();
            if (IS_WINDOWS) {
                canonical = canonical.toLowerCase(Locale.ROOT);
            }
            return "file:" + canonical;
        } catch (Exception e) {
            String fallback = path;
            if (IS_WINDOWS) {
                fallback = fallback.toLowerCase(Locale.ROOT);
            }
            return "file:" + fallback;
        }
    }

    /**
     * 将模型传入的文件路径参数解析为物理 File 对象
     */
    protected File resolveFile(String pathVal, AgentContext agentContext) {
        return projectService.resolvePath(pathVal, agentContext).toFile();
    }

    /**
     * 在运行时伴生上下文中记录文件的内容哈希（Key 经过规范化消除 Windows 大小写差异）
     */
    protected void recordFileHash(AgentContext agentContext, File file) {
        if (agentContext != null && file.exists() && file.isFile()) {
            RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
            if (runtimeCtx != null) {
                runtimeCtx.getReadFiles().put(FileHashSupport.toStorageKey(file),
                        FileHashSupport.computeFileHash(file));
            }
        }
    }

    /**
     * 修改类工具强制安全门禁：校验"读取过的内容仍与磁盘当前内容一致"，防止幻觉与过时修改。
     *
     * @param agentContext 智能体上下文
     * @param file         目标文件
     * @return 拦截时的错误提示文案；校验通过时返回 null 放行
     */
    protected String verifyReadBeforeWrite(AgentContext agentContext, File file) {
        if (agentContext == null) {
            return null;
        }
        String storageKey = FileHashSupport.toStorageKey(file);
        RuntimeContext runtimeCtx = agentContext.extra(RuntimeContext.class);
        String recordedHash = runtimeCtx != null ? runtimeCtx.getReadFiles().get(storageKey) : null;
        if (recordedHash == null) {
            log.warn("{} 安全防御触发：未读先改拦截 - {}", getClass().getSimpleName(), storageKey);
            return ToolResult.error("拒绝执行代码修改。门禁判定规则：read_file 成功读取过的文件才允许修改。当前状态：本会话尚未读取过该文件。"
                    + "请先使用 read_file 读取目标文件 [" + file.getName() + "] 的最新内容，然后重试修改。");
        }
        String currentHash = FileHashSupport.computeFileHash(file);
        if (!recordedHash.equals(currentHash)) {
            log.warn("{} 安全防御触发：文件内容已变化拦截 - {}", getClass().getSimpleName(), storageKey);
            return ToolResult.error("拒绝执行代码修改。目标文件 [" + file.getName() + "] 的内容自上次 read_file 后已发生变化"
                    + "（可能被外部程序、用户或其他工具修改）。请重新 read_file 读取最新内容后再重试修改，"
                    + "防止基于过时上下文产生错误替换。");
        }
        return null;
    }

    /**
     * 安全提取异常错误提示（防止 e.getMessage() 为 null）
     */
    protected String getSafeErrorMessage(Throwable e) {
        if (e == null) {
            return "未知异常";
        }
        String msg = e.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : e.getClass().getSimpleName();
    }
}
