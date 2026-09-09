package com.stioc.cute.mcp;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.engine.AgentEngine;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.mcp.McpClientInstance;
import com.stioc.cute.mcp.types.McpConfigWrapper;
import com.stioc.cute.mcp.types.McpServerConfig;
import com.stioc.cute.mcp.types.McpStatusVo;
import com.stioc.cute.mcp.types.McpToolVo;
import com.stioc.cute.platform.common.CharsetAwareFileKit;
import com.stioc.cute.platform.contract.ContractFile;
import com.stioc.cute.runtime.loop.RuntimeContext;
import com.stioc.cute.runtime.loop.RuntimeContextInitializer;
import com.stioc.cute.websocket.WebSocketBroadcast;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) 管理服务：统一托管所有 MCP 客户端实例的配置扫描、
 * 启动状态及进程复用回收逻辑
 */
@Slf4j
@Service
public class McpManagerService {

    @Resource
    @Lazy
    private AgentEngine agentEngine;

    /**
     * 全局 WebSocket 广播器：MCP 异步启动完成或工具集变动时向前端推送状态更新
     */
    @Resource
    private WebSocketBroadcast webSocketBroadcast;

    /**
     * 全局共享的 MCP 客户端进程缓存 (serverName -> McpClientInstance)
     */
    private final Map<String, McpClientInstance> sharedClients = new ConcurrentHashMap<>();

    /**
     * 为特定的会话上下文扫描并拉起/复用该项目专属的 MCP 客户端实例，绑定到 context 中。
     * <p>
     * 该方法由 {@link RuntimeContextInitializer#onContextRestored} 在会话创建/恢复主链路中同步调用，
     * 而 stdio 型 MCP 的进程拉起与握手可能因远端故障产生秒级阻塞，为避免拖慢会话创建接口的响应，
     * 仅对「需要重新启动的 stdio 实例」采用后台虚拟线程异步启动，启动完成后再回填绑定并广播工具就绪事件；
     * 其余逻辑（复用、配置合并、动态工具同步）保持同步语义。
     * </p>
     */
    public synchronized void loadAndStartForContext(AgentContext context, String projectBasePath) {
        if (context == null) {
            return;
        }

        RuntimeContext rc = context.extra(RuntimeContext.class);
        if (rc != null) {
            rc.getMcpClients().clear();
        }

        if (!StringUtils.hasText(projectBasePath)) {
            RuntimeContextInitializer.syncDynamicToolProviders(context);
            cleanupOrphanedClients(context);
            return;
        }

        // 加载当前项目合并后的 MCP 配置
        Map<String, McpServerConfig> configs = loadMergedMcpConfigs(projectBasePath);
        if (configs.isEmpty()) {
            log.info("会话 {} 的项目路径下未发现任何有效 MCP 配置文件", context.getCid());
            RuntimeContextInitializer.syncDynamicToolProviders(context);
            cleanupOrphanedClients(context);
            return;
        }

        // 记录需要异步启动的 stdio 客户端（serverName -> 待启动实例），主链路不等待其启动完成
        Map<String, McpClientInstance> asyncStarting = new LinkedHashMap<>();

        // 逐个拉起或复用共享客户端
        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig config = entry.getValue();

            McpClientInstance client = sharedClients.get(serverName);
            boolean needStart = false;

            if (client == null) {
                needStart = true;
            } else if ("OFFLINE".equals(client.getStatus())) {
                log.info("检测到 MCP 客户端进程 {} 已离线，将重新启动", serverName);
                client.shutdown();
                needStart = true;
            } else if (!config.equals(client.getConfig())) {
                log.info("检测到 MCP 客户端进程 {} 配置已变动，将重启服务子进程", serverName);
                client.shutdown();
                needStart = true;
            }

            if (needStart) {
                log.info("会话 {} 初始化 MCP 服务器: {} -> {}", context.getCid(), serverName, config);
                // 传入 projectBasePath 作为该共享进程拉起时的工作目录 (Cwd)
                client = new McpClientInstance(serverName, config, projectBasePath);
                client.setOnToolsChangedCallback(() -> {
                    log.info("MCP 服务器 {} 工具变动，准备重新装载...", serverName);
                    try {
                        McpClientInstance c = sharedClients.get(serverName);
                        if (c != null) {
                            c.refreshTools();
                        }
                        // 工具集变动后，向所有前端客户端广播 MCP 状态更新事件
                        broadcastMcpUpdated(context.getCid(), serverName);
                    } catch (Exception e) {
                        log.error("刷新工具失败: {}", serverName, e);
                    }
                });

                if (isStdioConfig(config)) {
                    // stdio 型：进程拉起与握手可能秒级阻塞，转后台虚拟线程异步启动，不拖慢会话创建主链路
                    asyncStarting.put(serverName, client);
                } else {
                    // SSE 型：远端 HTTP 服务握手轻量，保持原有同步启动语义
                    try {
                        client.start();
                        sharedClients.put(serverName, client);
                        log.info("MCP 服务器 {} 启动成功，暴露工具数: {}", serverName, client.getExposedTools().size());
                    } catch (Exception ex) {
                        log.error("启动 MCP 服务器 {} 失败", serverName, ex);
                        // 即使拉起进程或连接失败，也存入共享 Map 以便跟踪其状态
                        sharedClients.put(serverName, client);
                    }
                }
            } else {
                log.info("会话 {} 复用已存在的全局 MCP 服务器实例: {}", context.getCid(), serverName);
            }

            // 将共享实例绑定到当前上下文，无论其是否在线（OFFLINE 状态的服务器同样需要被绑定并返回给前端展示）
            if (client != null && rc != null) {
                rc.getMcpClients().put(serverName, client);
            }
        }

        // 后台虚拟线程逐个异步启动 stdio 实例：启动完成后回填共享缓存并广播事件，供前端刷新 MCP 看板
        for (Map.Entry<String, McpClientInstance> asyncEntry : asyncStarting.entrySet()) {
            String serverName = asyncEntry.getKey();
            McpClientInstance client = asyncEntry.getValue();
            Thread.startVirtualThread(() -> {
                try {
                    client.start();
                    sharedClients.put(serverName, client);
                    log.info("MCP 服务器 {} 异步启动成功，暴露工具数: {}", serverName, client.getExposedTools().size());
                    broadcastMcpUpdated(context.getCid(), serverName);
                } catch (Exception ex) {
                    log.error("MCP 服务器 {} 异步启动失败", serverName, ex);
                    // 即使拉起进程或连接失败，也存入共享 Map 以便跟踪其状态
                    sharedClients.put(serverName, client);
                    broadcastMcpUpdated(context.getCid(), serverName);
                }
            });
        }

        // 装配完成后，统一同步动态工具提供者到引擎上下文
        RuntimeContextInitializer.syncDynamicToolProviders(context);

        // 统一回收不被任何活动会话依赖的孤立 MCP 进程
        cleanupOrphanedClients(context);
    }

    /**
     * 判断 MCP 配置是否为 stdio 本地进程型（command 非 http/https 开头）
     */
    private boolean isStdioConfig(McpServerConfig config) {
        String command = config == null ? null : config.getCommand();
        return command != null && !command.startsWith("http://") && !command.startsWith("https://");
    }

    /**
     * 广播 MCP 状态更新事件（异步启动完成/失败、工具集变动时调用，驱动前端刷新 MCP 看板）
     */
    private void broadcastMcpUpdated(Long cid, String serverName) {
        if (webSocketBroadcast == null) {
            return;
        }
        try {
            webSocketBroadcast.broadcast(WebSocketBroadcast.EventType.MCP_UPDATED, cid, getMcpStatusList());
        } catch (Exception e) {
            log.error("广播 MCP_UPDATED 事件失败: {}", serverName, e);
        }
    }

    /**
     * 解绑特定会话的所有专属 MCP 客户端引用并清理孤立进程
     */
    public synchronized void shutdownForContext(AgentContext context) {
        if (context == null) {
            return;
        }
        // 只清空绑定关系，实现物理隔离下的进程共享
        RuntimeContext rc = context.extra(RuntimeContext.class);
        if (rc != null) {
            rc.getMcpClients().clear();
        }
        RuntimeContextInitializer.syncDynamicToolProviders(context);
        cleanupOrphanedClients(null);
    }

    /**
     * 清理物理内存中未被任何活动会话绑定的孤立 MCP 客户端实例，防止内存泄漏和孤儿进程
     */
    private void cleanupOrphanedClients(AgentContext currentInitializingContext) {
        if (agentEngine == null) {
            return;
        }
        Collection<AgentContext> allContexts = agentEngine.getContextFacade().getAllContexts();
        List<AgentContext> contextsToCheck = new ArrayList<>();
        if (allContexts != null) {
            contextsToCheck.addAll(allContexts);
        }
        if (currentInitializingContext != null && !contextsToCheck.contains(currentInitializingContext)) {
            contextsToCheck.add(currentInitializingContext);
        }

        for (String serverName : new ArrayList<>(sharedClients.keySet())) {
            boolean inUse = false;
            for (AgentContext ctx : contextsToCheck) {
                RuntimeContext rc = ctx.extra(RuntimeContext.class);
                if (rc != null && rc.getMcpClients().containsKey(serverName)) {
                    inUse = true;
                    break;
                }
            }
            if (!inUse) {
                McpClientInstance client = sharedClients.remove(serverName);
                if (client != null) {
                    log.info("检测到未被任何活动会话使用的孤立 MCP 客户端进程 [{}]，开始回收并关闭...", serverName);
                    try {
                        client.shutdown();
                    } catch (Exception e) {
                        log.error("关闭孤立 MCP 客户端进程 [{}] 异常", serverName, e);
                    }
                }
            }
        }
    }

    /**
     * 获取所有节点的连接及工具状态列表供前端看板读取
     */
    public List<McpStatusVo> getMcpStatusList() {
        return getMcpStatusList((AgentContext) null);
    }

    /**
     * 获取指定会话上下文下的连接及工具状态列表
     */
    public List<McpStatusVo> getMcpStatusList(AgentContext context) {
        List<McpStatusVo> statusList = new ArrayList<>();
        if (context != null) {
            RuntimeContext rc = context.extra(RuntimeContext.class);
            if (rc != null) {
                // 只返回当前会话上下文绑定的专属 MCP 客户端状态，避免展示全局其他项目的缓存实例
                addClientStatusList(rc.getMcpClients().values(), statusList);
            }
        } else {
            // 降级返回所有共享客户端的状态列表
            addClientStatusList(sharedClients.values(), statusList);
        }
        return statusList;
    }

    /**
     * 加载合并三级 MCP 服务配置（覆盖型契约）：
     * 读取顺序 全局级 → 项目通用级 → 项目级，同名服务后写覆盖，项目级最终生效
     */
    private Map<String, McpServerConfig> loadMergedMcpConfigs(String projectBasePath) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        // 1. 加载全局级配置：~/.st-cute/mcp_servers.json
        File globalMcpFile = ContractFile.getGlobalMcpServersFile();
        if (globalMcpFile != null && globalMcpFile.exists()) {
            try {
                String jsonStr = CharsetAwareFileKit.readString(globalMcpFile.toPath());
                McpConfigWrapper wrapper = JSON.parseObject(jsonStr, McpConfigWrapper.class);
                if (wrapper != null && wrapper.getMcpServers() != null) {
                    merged.putAll(wrapper.getMcpServers());
                }
            } catch (Exception e) {
                log.error("加载全局级 mcp_servers.json 异常", e);
            }
        }

        // 2. 加载项目配置：按 项目通用级 → 项目级 遍历（后读覆盖，项目级最终生效）
        if (projectBasePath != null) {
            ContractFile.forEachProjectFile(projectBasePath, ContractFile.FILE_MCP_SERVERS, projectMcpFile -> {
                try {
                    String jsonStr = CharsetAwareFileKit.readString(projectMcpFile.toPath());
                    McpConfigWrapper wrapper = JSON.parseObject(jsonStr, McpConfigWrapper.class);
                    if (wrapper != null && wrapper.getMcpServers() != null) {
                        merged.putAll(wrapper.getMcpServers());
                    }
                } catch (Exception e) {
                    log.error("加载项目级 {} mcp_servers.json 异常", projectMcpFile.getParentFile().getName(), e);
                }
            });
        }

        return merged;
    }

    private void addClientStatusList(Collection<McpClientInstance> clients, List<McpStatusVo> statusList) {
        for (McpClientInstance client : clients) {
            addClientStatus(client, statusList);
        }
    }

    private void addClientStatus(McpClientInstance client, List<McpStatusVo> statusList) {
        List<McpToolVo> toolsInfo = new ArrayList<>();
        client.getExposedTools().forEach(t -> {
            toolsInfo.add(new McpToolVo(
                    t.getName(),
                    t.getDescription(),
                    t.getArgumentSchema()
            ));
        });
        statusList.add(new McpStatusVo(
                client.getName(),
                client.getStatus(),
                client.isSse() ? "sse" : "stdio",
                toolsInfo
        ));
    }

    private void shutdownAll() {
        log.info("开始关闭并销毁所有缓存的全局共享 MCP 客户端子进程...");
        for (McpClientInstance client : sharedClients.values()) {
            try {
                client.shutdown();
            } catch (Exception e) {
                log.error("关闭 MCP 客户端 {} 异常", client.getName(), e);
            }
        }
        sharedClients.clear();
    }

    /**
     * JVM 关闭或 Bean 销毁，释放所有全局共享 MCP 进程资源，防止遗留孤儿进程
     */
    @PreDestroy
    public void cleanup() {
        log.info("JVM 关闭或 Bean 销毁，开始释放所有全局共享 MCP 进程资源，防止遗留孤儿进程...");
        shutdownAll();
    }
}
