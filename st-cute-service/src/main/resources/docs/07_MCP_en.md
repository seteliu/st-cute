# MCP Integration Guide

[简体中文](./07_MCP.md) | [English](./07_MCP_en.md)

ST-Cute features a built-in open-source **MCP (Model Context Protocol)** client standard implementation, enabling agents to mount and invoke external MCP Servers for seamless integration with external databases, APIs, toolchains, and system contexts.

---

## 🔌 1. MCP Architecture & Configuration Files

ST-Cute manages external MCP server launching and connection via `mcp_servers.json`:

* **Global Configuration File**: `~/.st-cute/mcp_servers.json`
* **Project Configuration File**: `{projectBasePath}/.agents/mcp_servers.json` or `{projectBasePath}/.st-cute/mcp_servers.json`

---

## ⚙️ 2. `mcp_servers.json` Configuration Specification

Supports configuring multiple independent MCP servers (supporting both `stdio` standard I/O and `sse` asynchronous streaming modes). Field reference:

| Field Name | Type | Description |
| :--- | :--- | :--- |
| **`command`** | String | `stdio` mode: launch command (e.g. `node`, `npx`, `uvx` or an executable); `sse` mode: a remote URL starting with `http://` or `https://` |
| **`args`** | List | Launch argument list (`stdio` mode only) |
| **`env`** | Map | Environment variables for the subprocess (`stdio` mode only, optional) |

Configuration example:

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
> **SSE Mode Trigger Rule**: No extra `type` field is needed. When `command` starts with `http://` or `https://`, the system automatically switches to native SSE remote connection (GET to establish the `text/event-stream` receiving channel; the server publishes the POST messaging endpoint via an `endpoint` event).

---

## 🚀 3. Communication Protocol & Runtime Mechanics

The underlying layer interacts with MCP Servers via the standard **JSON-RPC 2.0** protocol, declaring protocol version `2024-11-05` during handshake:

* **Handshake Flow**: After the connection is established, the `initialize` handshake and `notifications/initialized` notification are completed automatically, followed by `tools/list` to sync the initial tool list.
* **Timeout Mechanism**: SSE connection establishment waits 5s, handshake 10s, tool list sync 10s, and a single tool execution **60s**; timeouts fail gracefully while process status remains traceable.
* **Tool Name Conflict Prevention**: MCP tools are automatically prefixed with `{serverName}_` when mounted to the agent (e.g. `git_status`), avoiding naming conflicts with built-in local tools or other MCP servers' tools.
* **Tool Result Extraction**: `text` type content blocks returned by the server are aggregated as the tool execution result; results flagged with `isError` are wrapped as error messages back to the model.

---

## 📊 4. Backend Lifecycle Management

Backend `McpManagerServiceImpl` provides safe and efficient process lifecycle management for MCP clients:

* **Shared Clients Across Sessions**: Manages a global `sharedClients` cache. Sessions relying on the same MCP server automatically share underlying subprocesses.
* **Working Directory Binding**: Subprocesses automatically set their Working Directory (Cwd) to current project root path.
* **Config Merge Strategy**: At session assembly, the global `mcp_servers.json` is read first, then project-level configs are merged (project-level takes precedence for same-named servers; `.agents` directory takes priority over `.st-cute`).
* **Config Comparison & Auto Restart**: At each session assembly, configurations are compared against running process snapshots. Upon config changes or offline processes, old processes are shut down, re-launched, and JSON-RPC handshakes are completed automatically.
* **Orphan Process Auto-Cleanup**: Safely kills and gracefully closes idle MCP subprocesses when no active sessions reference them, preventing memory leaks and orphaned processes; all shared process resources are released uniformly on JVM shutdown.
* **Dynamic Tool Mounting**: Supports `onToolsChangedCallback` callback. When an external MCP Server pushes `notifications/tools/list-changed`, the tool list refreshes automatically and the WebUI inspector and agent tool registry update in real time.
