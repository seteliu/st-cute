package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolNames;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.event.AgentEventFactory;
import com.stioc.cute.conversation.access.ConversationEntity;
import com.mybatisflex.core.util.UpdateEntity;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具发现工具 (discover_tools)
 */
@Slf4j
@Component
public class DiscoverToolsTool implements CuteTool {

    @Resource
    private ApplicationContext applicationContext;

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.DISCOVER_TOOLS;
    }

    @Override
    public String getDescription() {
        return "【工具发现与解锁核心工具】通过关键字模糊搜索系统中隐藏的‘按需暴露’的专属高级工具（如 git worktree 相关的进入/退出隔离副本工具）。搜索匹配成功后将自动解锁并在后续轮次中加载到你的工具列表。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "进行工具检索匹配的关键词，如：'worktree'、'git'"
            }
          },
          "required": ["query"]
        }
        """;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        AgentContext agentContext = context.agentContext();
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) {
            return new JSONObject().fluentPut("error", "参数 'query' 不能为空。").toJSONString();
        }

        if (agentContext == null) {
            return new JSONObject().fluentPut("error", "无法获取当前 AgentContext 对话控制状态，无法解锁").toJSONString();
        }

        String lowerQuery = query.toLowerCase().trim();
        List<String> unlockedNames = new ArrayList<>();
        List<String> newToolNames = new ArrayList<>();
        Set<String> nextUnlockedSet = new HashSet<>(agentContext.getUnlockedTools());

        // 获取 Spring 容器里所有的 CuteTool 实现，进行完整查找
        Map<String, CuteTool> allBeans = applicationContext.getBeansOfType(CuteTool.class);
        for (CuteTool tool : allBeans.values()) {
            // 只考虑按需暴露的工具
            if (tool.isExposeOnDemand()) {
                String name = tool.getName().toLowerCase();
                String desc = tool.getDescription().toLowerCase();
                if (name.contains(lowerQuery) || desc.contains(lowerQuery)) {
                    if (!agentContext.getUnlockedTools().contains(tool.getName())) {
                        newToolNames.add(tool.getName());
                    }
                    nextUnlockedSet.add(tool.getName());
                    unlockedNames.add(tool.getName() + " (" + tool.getDescription() + ")");
                }
            }
        }

        if (unlockedNames.isEmpty()) {
            return new JSONObject()
                    .fluentPut("success", true)
                    .fluentPut("message", "未搜索到匹配关键词 '" + query + "' 的隐藏工具。请尝试其他关键词（例如 'worktree'）。")
                    .toJSONString();
        }

        // 只有在发现新隐藏工具时，才发布差量更新
        if (!newToolNames.isEmpty()) {
            ConversationEntity updatePayload = UpdateEntity.of(ConversationEntity.class);
            updatePayload.setId(agentContext.getCid());
            updatePayload.setUnlockedToolNames("+" + String.join(",", newToolNames));
            agentContext.publishEvent(AgentEventFactory.createConversationUpdate(agentContext, updatePayload));
        }

        return new JSONObject()
                .fluentPut("success", true)
                .fluentPut("message", "成功发现并解锁了以下按需暴露工具，它们已挂载到你下一轮的可用工具列表里：\n"
                        + String.join("\n", unlockedNames))
                .toJSONString();
    }
}
