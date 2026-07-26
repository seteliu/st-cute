package com.stioc.cute.agent.access;

import com.stioc.cute.skill.access.Skill;
import com.stioc.cute.hook.access.HookRule;
import com.stioc.cute.mcp.access.McpStatusVo;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 统一会话环境上下文信息传输对象
 */
@Data
@Builder
public class AgentContextVo {
    /**
     * 会话唯一 ID
     */
    private Long cid;

    /**
     * 当前权限兜底模式
     */
    private String permissionMode;

    /**
     * 当前使用的供应商分组名称
     */
    private String providerGroup;

    /**
     * 当前使用的模型名称
     */
    private String providerModelName;

    /**
     * 智能体是否正在运行中
     */
    private boolean loopRunning;
    
    /**
     * 当前累计消耗的输入 Token 数
     */
    private long inputTokens;

    /**
     * 当前累计消耗的输出 Token 数
     */
    private long outputTokens;

    /**
     * 当前累计命中的缓存 Token 数
     */
    private long cachedTokens;
    
    /**
     * 绑定的按需技能包快照列表
     */
    private List<Skill> skills;

    /**
     * 绑定的钩子挂钩规则快照列表
     */
    private List<HookRule> hooks;

    /**
     * 绑定的 MCP 服务器状态快照列表
     */
    private List<McpStatusVo> mcpServers;

    /**
     * 绑定的项目开发指令与规范列表 (AGENTS.md)
     */
    private List<AgentRuleVo> rules;
}
