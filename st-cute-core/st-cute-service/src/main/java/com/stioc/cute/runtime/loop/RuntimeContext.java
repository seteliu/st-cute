package com.stioc.cute.runtime.loop;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.BaseAgentContext;
import com.stioc.cute.hook.types.HookRule;
import com.stioc.cute.mcp.McpClientInstance;
import com.stioc.cute.runtime.loop.types.AgentRuleVo;
import com.stioc.cute.skill.types.Skill;
import com.stioc.cute.tool.support.ActiveProcess;
import com.stioc.cute.tool.support.RepeatCommandTracker;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行时会话伴生上下文（Coding Agent 宿主环境专属会话级数据袋）。
 * <p>
 * 实现引擎 {@link BaseAgentContext} 伴生契约，生命周期与 {@link AgentContext} 严格同体：
 * 由 {@link RuntimeContextInitializer} 在引擎上下文装载扩展点中创建并通过 {@code putExtraContext} 挂接；
 * 在会话取消或销毁时由引擎自动调用 {@link #clear()} 级联销毁物理子进程；
 * 承载宿主强类型数据：技能、规则、Hook、MCP 客户端实例、文件已读哈希指纹、活跃物理子进程等。
 * </p>
 */
@Data
public class RuntimeContext implements BaseAgentContext {

    /**
     * 所属会话 ID
     */
    private final Long cid;

    /**
     * 当前会话专属的技能包列表
     */
    private final List<Skill> skills = new CopyOnWriteArrayList<>();

    /**
     * 当前会话专属的生命周期 Hook 规则列表
     */
    private final List<HookRule> hookRules = new CopyOnWriteArrayList<>();

    /**
     * 当前会话专属的项目开发指令与规范列表 (AGENTS.md)
     */
    private final List<AgentRuleVo> rules = new CopyOnWriteArrayList<>();

    /**
     * 当前会话专属的 MCP 客户端实例映射 (serverName -> McpClientInstance)
     */
    private final Map<String, McpClientInstance> mcpClients = new ConcurrentHashMap<>();

    /**
     * 当前会话生命周期内成功读取过的文件内容指纹集合（绝对路径 → 内容哈希，双重强化门禁）。
     * <p>修改类工具执行前校验哈希是否与磁盘当前内容一致——一致放行（防幻觉），
     * 不一致拦截并要求重读（防过时修改）；文件未变化时无需重复读取，消除长会话摩擦。</p>
     */
    private final Map<String, String> readFiles = new ConcurrentHashMap<>();

    /**
     * 当前会话下正在同步运行的外部物理子进程容器 (toolCallId -> ActiveProcess)
     */
    private final Map<String, ActiveProcess> activeProcesses = new ConcurrentHashMap<>();

    /**
     * 当前会话近期的命令重复执行追踪表 (命令指纹 -> 追踪器)
     */
    private final Map<String, RepeatCommandTracker> recentCommands = new ConcurrentHashMap<>();

    /**
     * 强行销毁当前会话下所有的外部物理子进程
     */
    public void destroyAllProcesses() {
        activeProcesses.forEach((toolCallId, activeProcess) -> {
            try {
                activeProcess.destroyForcibly();
            } catch (Exception ignored) {
            }
        });
        activeProcesses.clear();
    }

    /**
     * 实现 BaseAgentContext 契约：会话取消或销毁时由引擎自动级联调用释放物理资源。
     */
    @Override
    public void clear() {
        destroyAllProcesses();
    }
}
