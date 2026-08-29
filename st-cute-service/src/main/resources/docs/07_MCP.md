# MCP 协议接入指南

[简体中文](./07_MCP.md) | [English](./07_MCP_en.md)

ST-Cute 内置了开源 **MCP (Model Context Protocol)** 客户端标准实现，允许智能体挂载和调用外部的 MCP Server，实现对外部数据库、API、工具链与系统上下文的深度无缝扩展。

---

## 🔌 1. MCP 架构与配置文件

ST-Cute 通过 `mcp_servers.json` 配置文件管理外部 MCP 服务的拉起与连接：

* **全局配置文件**：`~/.st-cute/mcp_servers.json`
* **项目级配置文件**：`{projectBasePath}/.agents/mcp_servers.json` 或 `{projectBasePath}/.st-cute/mcp_servers.json`

---

## ⚙️ 2. `mcp_servers.json` 配置规范

文件支持配置多个独立的 MCP 服务（支持 `stdio` 标准输入输出交互与 `sse` 异步传输模式）。配置项字段说明：

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| **`command`** | String | `stdio` 模式：启动命令（如 `node`、`npx`、`uvx` 或可执行文件）；`sse` 模式：以 `http://` 或 `https://` 开头的远端服务 URL |
| **`args`** | List | 启动参数列表（仅 `stdio` 模式） |
| **`env`** | Map | 启动子进程所需的环境变量（仅 `stdio` 模式，可选） |

配置示例：

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
      ],
      "env": {
        "GIT_TOKEN_ENV": "your-env-value"
      }
    },
    "remote-sse": {
      "command": "https://your-server.com/mcp/sse"
    }
  }
}
```

> [!TIP]
> **SSE 模式触发规则**：无需额外的 `type` 字段，系统检测到 `command` 以 `http://` 或 `https://` 开头时，将自动切换为原生 SSE 远程连接（GET 建立 `text/event-stream` 接收通道，服务端通过 `endpoint` 事件下发 POST 消息端点）。

---

## 🚀 3. 通信协议与运行机制

底层基于 **JSON-RPC 2.0** 标准协议与 MCP Server 交互，握手声明协议版本 `2024-11-05`：

* **握手流程**：连接建立后自动完成 `initialize` 握手与 `notifications/initialized` 通知，随后调用 `tools/list` 初始同步工具清单。
* **超时机制**：SSE 连接建立等待 5 秒、握手 10 秒、工具列表同步 10 秒、单次工具执行 **60 秒** 超时，超时将失败返回并保持进程状态可追踪。
* **工具名防冲突**：MCP 工具挂载到智能体时自动添加 `{服务名}_` 前缀（如 `git_status`），避免与本地内置工具或其他 MCP 服务的工具命名冲突。
* **工具结果提取**：自动聚合服务端返回的 `text` 类型内容块作为工具执行结果；`isError` 标记的错误结果会包装为错误信息返回给模型。

---

## 📊 4. 后端进程管理与生命周期

后端 `McpManagerServiceImpl` 为 MCP 客户端进程提供了高效、安全的生命周期托管：

* **多租户进程共享**：全局维护 `sharedClients` 客户端缓存。相同时期的不同会话若依赖同一 MCP 服务，将自动复用底层子进程。
* **物理工作目录绑定**：拉起 MCP 子进程时，自动将其 Working Directory (Cwd) 设置为当前项目物理根路径。
* **配置合并策略**：会话装配时先读取全局 `mcp_servers.json`，再合并项目级配置（同名服务以项目级为准，`.agents` 目录优先于 `.st-cute`）。
* **配置比对与自动重启**：每次会话装配时将配置与运行中进程的配置快照比对，发现配置变动或进程已离线时自动关闭旧进程、重新拉起并完成 JSON-RPC 握手。
* **孤立进程自动回收**：当某个 MCP Server 不再被任何活跃会话所使用时，后台将自动安全销毁并优雅关闭子进程，防止遗留孤儿进程与内存泄漏；JVM 关闭时统一释放全部共享进程资源。
* **工具动态挂载**：支持 `onToolsChangedCallback` 回调，外部 MCP Server 推送 `notifications/tools/list-changed` 通知时，工具清单自动刷新，前端看板与智能体工具库即时同步。
