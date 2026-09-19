package com.stioc.cute.engine.tool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * AI 智能体工具统一契约接口。
 * <p>
 * 【方法编排规范】：为保证代码阅读与维护一致性，接口内方法严格遵循以下优先级排序（不可随意调换）：
 * <ol>
 *   <li>标识与域定义：{@link #getDomain()} → {@link #getRawName()} → {@link #getName()}</li>
 *   <li>模型元数据定义：{@link #getDescription()} → {@link #getArgumentSchema()}</li>
 *   <li>生命周期与治理控制：{@link #isAvailable(AgentContext)} → {@link #isApprovalExempt()} → {@link #getAccessLevel()}</li>
 *   <li>并发锁与审计资源：{@link #getTargetResource(Map, AgentContext)} → {@link #getLockKey(Map, AgentContext)}</li>
 *   <li>执行入口：{@link #execute(Map, ToolExecutionContext)}（永远放置于最底部）</li>
 * </ol>
 * 【实现方规范】：实现类应严格对齐上述顺序组织重写方法；使用默认行为的方法无须冗余覆写；私有辅助方法统一置于 execute() 之后。
 * </p>
 */
public interface CuteTool {

    // ── 1. 标识与域定义 ──

    /**
     * 工具所属的隔离域标识（可选）。
     * 基础内置工具无域隔离需求，默认返回 null（表示全局根命名空间，无前缀）。
     * 外部扩展工具（如 MCP）返回其域标识（例如 "mcp__github"）。
     */
    default String getDomain() {
        return null;
    }

    /**
     * 工具在所属域内的原生名称（例如 "read_file"、"invoke_subagent"、"create_issue"）。
     */
    String getRawName();

    /**
     * 工具对外暴露的全局通信全名（大模型 Function Calling 协议全名）。
     * 默认规则：
     * - 若 domain 为空：通信全名即为原生名称 getRawName()；
     * - 若 domain 非空：自动按双下划线拼接为 "{domain}__{rawName}"。
     */
    default String getName() {
        String domain = getDomain();
        if (StringUtils.isBlank(domain)) {
            return getRawName();
        }
        return domain + "__" + getRawName();
    }

    // ── 2. 模型元数据定义 ──

    /**
     * 给大模型看的工具功能描述，指导模型合适时机调用
     */
    String getDescription();

    /**
     * 工具的参数 Schema 定义 (JSON Schema 格式字符串)
     */
    String getArgumentSchema();

    // ── 3. 生命周期与治理控制 ──

    /**
     * 该工具在当前会话上下文中是否可用。
     * 默认为 true；特定工具（如子智能体工具禁止递归拉起）可根据上下文自决，消除引擎层硬编码工具名特判。
     */
    default boolean isAvailable(AgentContext context) {
        return true;
    }

    /**
     * 该工具是否属于免审批工具（如纯内部调度工具、零外部物理副作用，天然免于人工审批或安全守卫拦截）。
     * 默认为 false。
     */
    default boolean isApprovalExempt() {
        return false;
    }

    /**
     * 工具访问等级（读/写/敏感三档）。
     * 默认按 {@link ToolAccessLevel#SENSITIVE} 兜底：未显式声明的工具（如 MCP 外部工具）
     * 按最保守策略治理，除全部放行模式外一律人工审批，宁严勿漏。
     */
    default ToolAccessLevel getAccessLevel() {
        return ToolAccessLevel.SENSITIVE;
    }

    // ── 4. 并发锁与审计资源 ──

    /**
     * 获取工具当前调用的目标资源标识（如文件路径、URI、表名与主键等）。
     * 供生命周期 Hook 规则匹配与审计使用，默认返回 null。
     * 上下文随参传入，便于实现按会话/项目维度定制资源语义（如相对路径按项目根解析），为后续扩展预留。
     */
    default String getTargetResource(Map<String, Object> arguments, AgentContext context) {
        return null;
    }

    /**
     * 获取写工具执行时需要排他保护的锁键（Lock Key）。
     * 仅在访问等级非 READ 时参与并发控制。
     * 默认直接使用 getTargetResource(arguments, context)；工具也可自行拼接命名空间（如 "file:" + path、"db:user:" + id 等）。
     * 若返回 null 或空串，则表示不加细粒度条带锁。
     * 上下文随参传入：锁键解析基准必须与 execute 一致（如相对路径按项目根解析），
     * 避免「JVM 工作目录 ≠ 项目根」部署形态下同一文件以相对/绝对两种形态调用得到不同锁键、互斥失效。
     */
    default String getLockKey(Map<String, Object> arguments, AgentContext context) {
        return getTargetResource(arguments, context);
    }

    // ── 5. 执行入口 ──

    /**
     * 工具的执行入口（永远置于接口最底部）
     *
     * @param arguments 大模型传递过来的参数 Map
     * @param context   显式的工具执行上下文
     * @return 执行结果的纯文本或 JSON 格式字符串，将回灌给大模型历史
     */
    String execute(Map<String, Object> arguments, ToolExecutionContext context);
}

