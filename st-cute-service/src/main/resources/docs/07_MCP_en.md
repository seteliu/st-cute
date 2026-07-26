# MCP Integration Guide

[简体中文](./07_MCP.md) | [English](./07_MCP_en.md)

ST-Cute features a built-in open-source **MCP (Model Context Protocol)** client standard implementation, enabling agents to mount and invoke external MCP Servers for seamless integration with external databases, APIs, toolchains, and system contexts.

---

## 🔌 1. MCP Architecture & Configuration Files

ST-Cute manages external MCP server launching and connection via `mcp_servers.json`:

* **Global Configuration File**: `~/.st-cute/mcp_servers.json`
* **Project Configuration File**: `{projectBasePath}/.agents/mcp_servers.json` or `{projectBasePath}/.st-cute/mcp_servers.json`

---

## ⚙️ 2. `mcp_servers.json` Configuration Example

Supports configuring multiple independent MCP servers (supporting both `stdio` standard I/O and `sse` asynchronous streaming modes):

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

## 🚀 3. Backend Lifecycle Management

Backend `McpManagerServiceImpl` provides safe and efficient process lifecycle management for MCP clients:

* **Shared Clients Across Sessions**: Manages a global `sharedClients` cache. Sessions relying on the same MCP server automatically share underlying subprocesses.
* **Working Directory Binding**: Subprocesses automatically set their Working Directory (Cwd) to current project root path.
* **Hot Sensing & Auto Restart**: Monitors file changes in `mcp_servers.json`. Re-launches processes and completes JSON-RPC handshakes upon configuration updates or process offline events.
* **Orphan Process Auto-Cleanup**: Safely kills and gracefully closes idle MCP subprocesses when no active sessions reference them, preventing memory leaks and orphaned processes.
* **Dynamic Tool Mounting**: Supports `onToolsChangedCallback` callback. When external MCP Servers update tools, the WebUI inspector and agent tool registry update in real time.
