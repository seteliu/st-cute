package com.stioc.cute.runtime.loop;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.loop.core.BaseAgentContext;
import com.stioc.cute.hook.types.HookRule;
import com.stioc.cute.mcp.McpClientInstance;
import com.stioc.cute.runtime.loop.types.AgentRuleVo;
import com.stioc.cute.skill.types.Skill;
import com.stioc.cute.tool.commandtool.ActiveProcess;
import com.stioc.cute.tool.commandtool.RepeatCommandTracker;
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
     * 当前会话生命周期内成功读取过的文件内容指纹集合（绝对路径 → 内容哈希，供覆写门禁与已读白名单消费）。
     * <p>write_file 覆写已存在的非空文件前校验哈希是否与磁盘当前内容一致——一致放行（防止未见过现有内容的盲目推平），
     * 不一致拦截并要求重读（防过时覆写）；edit_file 靠 oldContent 唯一匹配自证，不消费本集合做前置校验。
     * 另供 PermissionService 已读文件白名单放行（层级 5.5）比对。</p>
     * <p><b>生命周期约束</b>：本集合为纯进程内存态，不落库、不持久化，服务重启后随上下文一起归零。
     * 重启后历史对话中的 read_file 结果虽仍留在消息表里，但本记录不被恢复，
     * 故会被门禁视为"未读先写"拦截——这是设计取舍（宁可多读一轮，也不承认可能过时的事实），
     * 拦截文案已向模型显式说明该生命周期，避免其误判为工具故障。</p>
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
