# 文件规约

[简体中文](./02_file_conventions.md) | [English](./02_file_conventions_en.md)

本文档说明 ST-Cute 后端通过 `ContractFile` 统一管理的各级配置文件与目录读取/写入契约规约。

---

## 📁 配置文件分级与目录结构

ST-Cute 采用三级配置目录体系，按照全局、项目级与本地加白级进行隔离与覆盖：

```text
├── ~/.st-cute/                               # 全局级配置目录 (用户主目录)
│   ├── AGENTS.md                             # 全局通用开发指令与规范
│   ├── mcp_servers.json                      # 全局 MCP 工具服务配置
│   ├── skills/                               # 全局技能包目录
│   ├── hooks.json                            # 全局生命周期钩子
│   ├── permission.json                       # 全局权限安全规则
│   ├── config.json                           # 全局大模型供应商与系统配置
│   ├── st-cute.db                            # SQLite 数据库
│   └── logs/http-log.log                     # 全局 HTTP 载荷交互探针日志
│
├── {projectBasePath}/.agents/ 或 .st-cute/   # 项目级配置目录 (工作区根目录)
│   ├── AGENTS.md                             # 项目专有指令与开发规范
│   ├── mcp_servers.json                      # 项目专有 MCP 服务配置
│   ├── skills/                               # 项目专有技能包目录
│   ├── hooks.json                            # 项目专有生命周期钩子
│   ├── permission.json                       # 项目专有权限安全规则
│   └── permission_local.json                 # 本地审批加白规则 (系统自动维护)
```

---

## ⚙️ 契约级别与作用域说明

### 1. 全局级配置 (Global Level)
* **物理位置**：`~/.st-cute/`（当前系统用户主目录）
* **作用域**：对所有在 ST-Cute 中打开和操作的项目全局生效，提供基础的偏好配置、全局技能及全局 MCP 工具。

### 2. 项目级配置 (Project Level)
* **物理位置**：优先读取 `{projectBasePath}/.agents/` 目录；若不存在则使用 `{projectBasePath}/.st-cute/`；若同时存在则支持共存合并读取。
* **作用域**：仅对当前项目工作区生效。项目级配置可覆盖或叠加全局配置。

### 3. 本地加白级配置 (Local Permission Level)
* **物理位置**：`{projectBasePath}/.st-cute/permission_local.json`（或 `.agents/permission_local.json`）
* **作用域**：记录用户在当前项目 WebUI 中手动确认放行并加白的局部权限与路径审批规则，由系统自动生成与维护。

---

## 📜 规约文件汇总表

| 契约文件名/目录名 | 说明 | 支持级别 |
| :--- | :--- | :--- |
| **`AGENTS.md`** | 智能体开发规则与 Prompt 约束文件 | 全局 / 项目级 |
| **`mcp_servers.json`** | MCP (Model Context Protocol) 外部 Server 挂载配置 | 全局 / 项目级 |
| **`skills/`** | 可插拔技能包存放目录 | 全局 / 项目级 |
| **`hooks.json`** | 工具调用前后的生命周期拦截钩子 | 全局 / 项目级 |
| **`permission.json`** | 静态权限安全策略定义 | 全局 / 项目级 |
| **`permission_local.json`** | 运行时用户授权加白纪录（自动维护） | 本地项目级 |
| **`config.json`** | 大模型供应商（Supplier）配置与参数设置 | 全局级 |
| **`st-cute.db`** | SQLite 核心数据库文件 | 全局级 |
| **`logs/http-log.log`** | 大模型 HTTP 交互日志探针 | 全局级 |
