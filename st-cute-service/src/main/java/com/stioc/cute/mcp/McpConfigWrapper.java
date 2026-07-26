package com.stioc.cute.mcp;

import lombok.Data;
import java.util.Map;

/**
 * 对应 ~/.st-cute/mcp_servers.json 配置文件顶层结构
 */
@Data
public class McpConfigWrapper {
    private Map<String, McpServerConfig> mcpServers;
}
