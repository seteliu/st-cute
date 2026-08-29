# File Conventions

[简体中文](./02_file_conventions.md) | [English](./02_file_conventions_en.md)

This document describes the configuration file hierarchy and read/write contract specifications managed uniformly by `ContractFile` in the ST-Cute backend.

---

## 📁 Configuration Hierarchy & Directory Structure

ST-Cute adopts a 3-tier configuration directory system, isolated and overridden across Global, Project-level, and Local Permission Whitelist levels:

```text
├── ~/.st-cute/                               # Global configuration directory (User Home)
│   ├── AGENTS.md                             # Global development instructions & prompt rules
│   ├── mcp_servers.json                      # Global MCP tool server configurations
│   ├── skills/                               # Global skills directory
│   ├── hooks.json                            # Global lifecycle hooks
│   ├── permission.json                       # Global security permission rules
│   ├── config.json                           # Global LLM provider & system settings
│   ├── st-cute.db                            # SQLite database
│   └── logs/http-log.log                     # Global HTTP probe interaction logs
│
├── {projectBasePath}/.agents/ or .st-cute/   # Project-level directory (Workspace Root)
│   ├── AGENTS.md                             # Project-specific rules & instructions
│   ├── mcp_servers.json                      # Project-specific MCP servers
│   ├── skills/                               # Project-specific skills directory
│   ├── hooks.json                            # Project-specific lifecycle hooks
│   ├── permission.json                       # Project-specific security permission rules
│   └── permission_local.json                 # Local user permission whitelist (Auto-maintained)
```

---

## ⚙️ Contract Levels & Scope

### 1. Global Level Configuration
* **Physical Location**: `~/.st-cute/` (System User Home Directory)
* **Scope**: Applies globally across all projects opened in ST-Cute, providing base preferences, global skills, and global MCP tools.

### 2. Project Level Configuration
* **Physical Location**: Prefers `{projectBasePath}/.agents/`; falls back to `{projectBasePath}/.st-cute/`. Supports merged loading if both exist.
* **Scope**: Applies strictly to the current workspace. Project-level configurations override or stack on top of global settings.

### 3. Local Permission Whitelist Level
* **Physical Location**: `{projectBasePath}/.st-cute/permission_local.json` (or `.agents/permission_local.json`)
* **Scope**: Records local permissions and path approval whitelist rules manually confirmed by the user in the WebUI. Automatically created and maintained by the system.

---

## 📜 Contract Files Summary

| File / Directory Name | Description | Supported Levels |
| :--- | :--- | :--- |
| **`AGENTS.md`** | Agent development rules and prompt constraint definitions | Global / Project |
| **`mcp_servers.json`** | MCP (Model Context Protocol) external Server configurations | Global / Project |
| **`skills/`** | Pluggable skills package storage directory | Global / Project |
| **`hooks.json`** | Tool execution lifecycle interception hooks | Global / Project |
| **`permission.json`** | Static security permission policy definitions | Global / Project |
| **`permission_local.json`** | Runtime user authorization whitelist records (Auto-maintained) | Local Project |
| **`config.json`** | LLM Provider configurations and system parameters | Global |
| **`st-cute.db`** | SQLite core database file | Global |
| **`logs/http-log.log`** | Raw LLM HTTP interaction probe logs | Global |
