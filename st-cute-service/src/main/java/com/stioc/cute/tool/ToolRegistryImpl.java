package com.stioc.cute.tool;

import lombok.extern.slf4j.Slf4j;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.mcp.access.McpClientInstance;
import com.stioc.cute.mcp.access.McpManagerService;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolRegistry;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体工具系统的注册中心，负责发现和包装所有 CuteTool 实现（包括内置静态工具与 MCP 等外部动态工具）
 */
@Slf4j
@Component
public class ToolRegistryImpl implements ToolRegistry {

    @Resource
    private List<CuteTool> staticTools;
    @Resource
    private McpManagerService mcpManagerService;

    @PostConstruct
    public void init() {
        log.info("发现并初始化工具注册中心，静态工具共加载了 {} 个", staticTools.size());
        for (CuteTool tool : staticTools) {
            log.info("已注册静态工具: {}", tool.getName());
        }
    }

    /**
     * 获取当前所有可用工具的快照
     */
    public List<CuteTool> getAllTools(AgentContext context) {
        List<CuteTool> all = new ArrayList<>(staticTools);

        // 1. 获取当前会话专属的 MCP 动态工具（会话物理隔离）
        if (context != null) {
            for (McpClientInstance client : context.getMcpClients().values()) {
                if ("RUNNING".equals(client.getStatus())) {
                    all.addAll(client.getExposedTools());
                }
            }
        }

        // 2. 融合加载全局共享的 MCP 动态工具
        if (mcpManagerService != null) {
            List<CuteTool> globalDyns = mcpManagerService.getGlobalExposedTools();
            for (CuteTool gt : globalDyns) {
                if (all.stream().noneMatch(t -> t.getName().equalsIgnoreCase(gt.getName()))) {
                    all.add(gt);
                }
            }
        }

        // 3. 门禁与按需曝光控制过滤
        if (context != null) {
            all.removeIf(t -> t.isExposeOnDemand() && !context.getUnlockedTools().contains(t.getName()));
        }
        return all;
    }

    /**
     * 根据名称查找工具（精确匹配工具的 getName() 返回值，忽略大小写）
     */
    public CuteTool getTool(String name, AgentContext context) {
        return getAllTools(context).stream()
                .filter(t -> t.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
