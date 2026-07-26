package com.stioc.cute.mcp;

import com.stioc.cute.mcp.access.McpToolVo;
import com.stioc.cute.mcp.access.McpStatusVo;
import com.stioc.cute.mcp.access.McpClientInstance;
import com.stioc.cute.mcp.access.*;

import com.alibaba.fastjson2.JSON;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.agent.access.AgentContext;
import com.stioc.cute.agent.access.AgentContextManager;
import com.stioc.cute.platform.contract.ContractFile;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collection;

/**
 * 统一托管所有 MCP 客户端实例的配置扫描、启动状态及进程复用回收逻辑
 */
@Slf4j
@Service
public class McpManagerServiceImpl implements McpManagerService {

    @Resource
    @Lazy
    private AgentContextManager agentContextManager;

    /**
     * 全局共享的 MCP 客户端进程缓存 (serverName -> McpClientInstance)
     */
    private final Map<String, McpClientInstance> sharedClients = new ConcurrentHashMap<>();

    /**
     * 为特定的会话上下文扫描并拉起/复用该项目专属的 MCP 客户端实例，绑定到 context 中
     */
    public synchronized void loadAndStartForContext(AgentContext context, String projectBasePath) {
        if (context == null) {
            return;
        }

        // 仅解绑绑定关系，在装配结束后统一物理清理孤立进程
        context.getMcpClients().clear();

        if (!StringUtils.hasText(projectBasePath)) {
            cleanupOrphanedClients(context);
            return;
        }

        // 加载当前项目合并后的 MCP 配置
        Map<String, McpServerConfig> configs = loadMergedMcpConfigs(projectBasePath);
        if (configs.isEmpty()) {
            log.info("会话 {} 的项目路径下未发现任何有效 MCP 配置文件", context.getCid());
            cleanupOrphanedClients(context);
            return;
        }

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
                log.info("会话 {} 初始化并启动 MCP 服务器: {} -> {}", context.getCid(), serverName, config);
                // 传入 projectBasePath 作为该共享进程拉起时的工作目录 (Cwd)
                client = new McpClientInstance(serverName, config, projectBasePath);
                client.setOnToolsChangedCallback(() -> {
                    log.info("MCP 服务器 {} 工具变动，准备重新装载...", serverName);
                    try {
                        McpClientInstance c = sharedClients.get(serverName);
                        if (c != null) {
                            c.refreshTools();
                        }
                    } catch (Exception e) {
                        log.error("刷新工具失败: {}", serverName, e);
                    }
                });

                try {
                    client.start();
                    sharedClients.put(serverName, client);
                    log.info("MCP 服务器 {} 启动成功，暴露工具数: {}", serverName, client.getExposedTools().size());
                } catch (Exception ex) {
                    log.error("启动 MCP 服务器 {} 失败", serverName, ex);
                    // 即使拉起进程或连接失败，也存入共享 Map 以便跟踪其状态
                    sharedClients.put(serverName, client);
                }
            } else {
                log.info("会话 {} 复用已存在的全局 MCP 服务器实例: {}", context.getCid(), serverName);
            }

            // 将共享实例绑定到当前上下文，无论其是否在线（OFFLINE 状态的服务器同样需要被绑定并返回给前端展示）
            if (client != null) {
                context.getMcpClients().put(serverName, client);
            }
        }

        // 装配完成后，统一回收不被任何活动会话依赖的孤立 MCP 进程
        cleanupOrphanedClients(context);
    }

    /**
     * 解绑特定会话的所有专属 MCP 客户端引用并清理孤立进程
     */
    public synchronized void shutdownForContext(AgentContext context) {
        if (context == null) {
            return;
        }
        // 只清空绑定关系，实现物理隔离下的进程共享
        context.getMcpClients().clear();
        cleanupOrphanedClients(null);
    }

    /**
     * 清理物理内存中未被任何活动会话绑定的孤立 MCP 客户端实例，防止内存泄漏和孤儿进程
     */
    private void cleanupOrphanedClients(AgentContext currentInitializingContext) {
        if (agentContextManager == null) {
            return;
        }
        Collection<AgentContext> allContexts = agentContextManager.getAllContexts();
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
                if (ctx != null && ctx.getMcpClients() != null && ctx.getMcpClients().containsKey(serverName)) {
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
     * 获取全局的所有 MCP 暴露工具列表（前向兼容，返回空）
     */
    public List<CuteTool> getGlobalExposedTools() {
        return new ArrayList<>();
    }

    private Map<String, McpServerConfig> loadMergedMcpConfigs(String projectBasePath) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        // 1. 加载全局配置：~/.st-cute/mcp_servers.json
        File globalMcpFile = ContractFile.getGlobalMcpServersFile();
        if (globalMcpFile != null && globalMcpFile.exists()) {
            try {
                String jsonStr = Files.readString(globalMcpFile.toPath(), StandardCharsets.UTF_8);
                McpConfigWrapper wrapper = JSON.parseObject(jsonStr, McpConfigWrapper.class);
                if (wrapper != null && wrapper.getMcpServers() != null) {
                    merged.putAll(wrapper.getMcpServers());
                }
            } catch (Exception e) {
                log.error("加载全局 mcp_servers.json 异常", e);
            }
        }

        // 2. 加载项目级配置：遍历所有存在的项目目录并加载合并
        if (projectBasePath != null) {
            ContractFile.forEachProjectFile(projectBasePath, ContractFile.FILE_MCP_SERVERS, projectMcpFile -> {
                try {
                    String jsonStr = Files.readString(projectMcpFile.toPath(), StandardCharsets.UTF_8);
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

    /**
     * 获取所有节点的连接及工具状态列表供前端看板读取
     */
    public List<McpStatusVo> getMcpStatusList() {
        return getMcpStatusList((AgentContext) null);
    }

    public List<McpStatusVo> getMcpStatusList(AgentContext context) {
        List<McpStatusVo> statusList = new ArrayList<>();
        if (context != null) {
            // 只返回当前会话上下文绑定的专属 MCP 客户端状态，避免展示全局其他项目的缓存实例
            addClientStatusList(context.getMcpClients().values(), statusList);
        } else {
            // 降级返回所有共享客户端的状态列表
            addClientStatusList(sharedClients.values(), statusList);
        }
        return statusList;
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

    @PreDestroy
    public void cleanup() {
        log.info("JVM 关闭或 Bean 销毁，开始释放所有全局共享 MCP 进程资源，防止遗留孤儿进程...");
        shutdownAll();
    }
}
