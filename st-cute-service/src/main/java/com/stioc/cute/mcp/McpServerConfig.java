package com.stioc.cute.mcp;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 对应单台 MCP 服务器节点的启动参数配置
 */
@Data
public class McpServerConfig {

    /**
     * 启动命令（如 node、python 或可执行二进制文件）
     */
    private String command;

    /**
     * 启动参数列表
     */
    private List<String> args;

    /**
     * 启动所需环境变量
     */
    private Map<String, String> env;
}
