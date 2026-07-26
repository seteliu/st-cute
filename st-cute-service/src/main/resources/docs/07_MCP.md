# MCP 协议接入指南

[简体中文](./07_MCP.md) | [English](./07_MCP_en.md)

ST-Cute 内置了开源 **MCP (Model Context Protocol)** 客户端标准实现，允许智能体挂载和调用外部的 MCP Server，实现对外部数据库、API、工具链与系统上下文的深度无缝扩展。

---

## 🔌 1. MCP 架构与配置文件

ST-Cute 通过 `mcp_servers.json` 配置文件管理外部 MCP 服务的拉起与连接：

* **全局配置文件**：`~/.st-cute/mcp_servers.json`
* **项目级配置文件**：`{projectBasePath}/.agents/mcp_servers.json` 或 `{projectBasePath}/.st-cute/mcp_servers.json`

---

## ⚙️ 2. `mcp_servers.json` 配置示例

文件支持配置多个独立的 MCP 服务（支持 `stdio` 标准输入输出交互与 `sse` 异步传输模式）：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/path/to/allowed/dir"
      ]
    },
    "git": {
      "command": "uvx",
      "args": [
        "mcp-server-git",
        "--repository",
        "/path/to/repo"
      ]
    }
  }
}
```

---

## 🚀 3. 后端进程管理与生命周期

后端 `McpManagerServiceImpl` 为 MCP 客户端进程提供了高效、安全的生命周期托管：

* **多租户进程共享 (Shared Clients)**：全局维护 `sharedClients` 客户端缓存。相同时期的不同会话若依赖同一 MCP 服务，将自动复用底层子进程。
* **物理工作目录绑定**：拉起 MCP 子进程时，自动将其 Working Directory (Cwd) 设置为当前项目物理根路径。
* **热配置感知与重启**：监测 `mcp_servers.json` 的文件变更，配置修改或进程离线时自动重新拉起并完成 JSON-RPC 握手。
* **孤立进程自动回收 (Orphan Process Cleanup)**：当某个 MCP Server 不再被任何活跃会话所使用时，后台将自动安全销毁并优雅关闭子进程，防止遗留孤儿进程与内存泄漏。
* **工具动态挂载**：支持 `onToolsChangedCallback` 回调，外部 MCP Server 增加或更新 Tool 时，前端看板与智能体工具库自动即时刷新。
