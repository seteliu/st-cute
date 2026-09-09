# File Conventions

[简体中文](./02_file_conventions.md) | [English](./02_file_conventions_en.md)

This document describes the configuration file hierarchy and read/write contract specifications managed uniformly by `ContractFile` in the ST-Cute backend.

---

## 📁 3-Tier Configuration Hierarchy

ST-Cute adopts a 3-tier configuration directory system. The level terminology and priorities (from high to low) are defined as:

| Level | Physical Location | Positioning |
| :--- | :--- | :--- |
| **Project-level** | `{projectBasePath}/.st-cute/` | Project-specific configuration, highest priority |
| **Project Common** | `{projectBasePath}/.agents/` | Project-shared configuration, suitable for Git versioning and team consistency |
| **Global** | `~/.st-cute/` (User Home) | Personal baseline configuration shared across all projects |

```text
├── ~/.st-cute/                               # Global configuration directory (User Home)
│   ├── AGENTS.md                             # Global development instructions & prompt rules
│   ├── rules/                                # Global rule sets (one file per rule, nested sub-directories supported)
│   ├── mcp_servers.json                      # Global MCP tool server configurations
│   ├── skills/                               # Global skills directory
│   ├── hooks.json                            # Global lifecycle hooks
│   ├── permission.json                       # Global security permission rules
│   ├── config.json                           # Global LLM provider & system settings
│   ├── st-cute.db                            # SQLite database
│   └── logs/http-log.log                     # Global HTTP probe interaction logs
│
├── {projectBasePath}/.st-cute/               # Project-level directory (project-specific, highest priority)
│   ├── AGENTS.md                             # Project-level rules & instructions
│   ├── rules/                                # Project-level rule sets (one file per rule, nested sub-directories supported)
│   ├── mcp_servers.json                      # Project-level MCP servers
│   ├── skills/                               # Project-level skills directory
│   ├── hooks.json                            # Project-level lifecycle hooks
│   ├── permission.json                       # Project-level security permission rules
│   └── permission_local.json                 # Local permission whitelist (auto-maintained, project-level only)
│
└── {projectBasePath}/.agents/                # Project Common directory (project-shared, suitable for Git)
    ├── AGENTS.md                             # Project Common rules & instructions
    ├── rules/                                # Project Common rule sets
    ├── mcp_servers.json                      # Project Common MCP servers
    ├── skills/                               # Project Common skills directory
    └── hooks.json                            # Project Common lifecycle hooks
```

---

## ⚙️ Level Merge Semantics (Two Contract Models)

Unified priority: **Project-level > Project Common > Global**. Two merge semantics apply by contract type:

### 1. Stacking Contracts (Rule, Hook)

* Configurations from **all three levels take effect** without overriding each other
* Loading/injection order: **Project-level → Project Common → Global** (higher priority is placed first in the list/prompt)

### 2. Overriding Contracts (Skill, MCP)

* Read order: **Global → Project Common → Project-level** (lower priority read first, higher priority overrides later)
* Same-named entries keep only one copy; the **project-level configuration takes final effect**

### 3. Exception: Permission

* Only **Global + Project-level + Local** levels are recognized; the Project Common level (`.agents`) is **completely excluded**
* Rule loading order: Global → Project-level → Local; the **last matching rule wins** during verdicts, so effective priority: Local whitelist > Project-level > Global
* The Local level (`permission_local.json`) holds auto-maintained "human-in-the-loop" whitelist records, always written to the project-level directory

---

## 📜 Contract Files Summary

| File / Directory Name | Description | Supported Levels | Merge Semantics |
| :--- | :--- | :--- | :--- |
| **`AGENTS.md`** | Agent development rules and prompt constraint definitions | Global / Project-level / Project Common | Stacking |
| **`rules/`** | Rule set sub-directory, one file per rule, supports `enable` metadata toggle and nested sub-directories | Global / Project-level / Project Common | Stacking |
| **`mcp_servers.json`** | MCP (Model Context Protocol) external Server configurations | Global / Project-level / Project Common | Overriding |
| **`skills/`** | Pluggable skills package storage directory | Global / Project-level / Project Common | Overriding |
| **`hooks.json`** | Tool execution lifecycle interception hooks | Global / Project-level / Project Common | Stacking |
| **`permission.json`** | Static security permission policy definitions | Global / Project-level | Stacking (last rule wins) |
| **`permission_local.json`** | Runtime user authorization whitelist records (Auto-maintained) | Local (project-level directory only) | Stacking (last rule wins) |
| **`config.json`** | LLM Provider configurations and system parameters | Global | - |
| **`st-cute.db`** | SQLite core database file | Global | - |
| **`logs/http-log.log`** | Raw LLM HTTP interaction probe logs | Global | - |
