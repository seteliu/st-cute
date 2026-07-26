# HOOK Interception Guide

[简体中文](./08_HOOK.md) | [English](./08_HOOK_en.md)

The HOOK mechanism provides aspect-oriented lifecycle interception for agent executions. By configuring `hooks.json`, developers can inject custom audit, argument validation, file filtering, or automation scripts before/after tool execution and LLM HTTP requests.

---

## ⚓ 1. Configuration Files & Paths

* **Global Hook Configuration**: `~/.st-cute/hooks.json`
* **Project Hook Configuration**: `{projectBasePath}/.agents/hooks.json` or `{projectBasePath}/.st-cute/hooks.json`

---

## ⚙️ 2. `hooks.json` Specification

Contains an array of Hook rules, each defining trigger events, filter conditions, and command arguments:

```json
[
  {
    "name": "check-java-formatting",
    "event": "beforeToolExecution",
    "toolFilter": "replace_file_content",
    "pattern": "*.java",
    "isBlocking": true,
    "args": {
      "command": "python scripts/validate_java.py ${path}"
    }
  },
  {
    "name": "audit-log-notifier",
    "event": "afterToolExecution",
    "isBlocking": false,
    "args": {
      "command": "node scripts/audit_notify.js"
    }
  }
]
```

### 📋 Field Specification

| Field Name | Type | Description |
| :--- | :--- | :--- |
| **`name`** | String | Name of the hook rule (Required) |
| **`event`** | String | Trigger event point (`beforeToolExecution` / `afterToolExecution`) |
| **`toolFilter`** | String | Specific tool name to trigger (e.g. `replace_file_content`; leave empty for all tools) |
| **`pattern`** | String | File path Glob pattern matching (e.g. `*.java` or `src/**`) |
| **`isBlocking`** | Boolean | Whether to block execution: `true` for synchronous blocking; `false` for async non-blocking |
| **`args.command`** | String | Shell/CMD command to execute, supporting `${path}` macro replacement |

---

## 🚀 3. Execution Engine & Data Passing

Backend `HookEngineServiceImpl` provides the following features during Hook execution:

1. **Macro Replacement**: Replaces `${path}` in commands with the normalized absolute path of the target file.
2. **Context Data Passing**: Writes runtime context (`HookContext`, containing session ID, tool name, arguments, and file path) to a temporary JSON file before spawning subprocesses, passed via environment variable:
   ```bash
   # Environment Variable
   ST_CUTE_HOOK_DATA_PATH=/tmp/st-cute_hook_ruleName_xxx.json
   ```
   Scripts can read this file path from the environment variable to access full invocation context.
3. **Timeout & Security Control**:
   - Strict 60-second timeout to kill hanging processes and prevent deadlocks;
   - In blocking mode, if the process exit code is non-zero (`!= 0`), the engine throws an exception and halts tool execution;
   - Real-time execution status (`running`, `success`, `failed`) is pushed via WebSocket to the frontend inspector.
