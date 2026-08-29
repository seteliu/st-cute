# HOOK Interception Guide

[简体中文](./08_HOOK.md) | [English](./08_HOOK_en.md)

The HOOK mechanism provides aspect-oriented lifecycle interception for agent executions. By configuring `hooks.json`, developers can inject custom audit, argument validation, file filtering, or automation scripts at key lifecycle points: session startup, loop start/end, before tool calls, and after tool completion.

---

## ⚓ 1. Configuration Files & Paths

* **Global Hook Configuration**: `~/.st-cute/hooks.json`
* **Project Hook Configuration**: `{projectBasePath}/.agents/hooks.json` or `{projectBasePath}/.st-cute/hooks.json`

Global rules apply to all sessions; project-level rules are loaded at session startup and merged with global rules. Same-named rules do not conflict (project rules are namespaced by project path).

---

## 🎯 2. Lifecycle Events Overview

The system currently supports **5** Hook event points. Lowercase snake_case is recommended for the `event` field (event names are case-insensitive):

| Event | Trigger Timing | Context Data Available | Blocking Effect |
| :--- | :--- | :--- | :--- |
| **`on_context_start`** | When the session environment is initialized (before the ReAct loop starts) | Session basic info only (`cid` etc., no tool data) | Failures are logged only, no impact on the main flow |
| **`on_loop_start`** | When a single reasoning step starts | Session basic info only (`cid` etc., no tool data) | Failures are logged only, no impact on the main flow |
| **`on_tool_call`** | When the agent initiates a tool call (not yet physically executed, before permission evaluation) | `toolCallId`, `toolName`, `toolArgs`, `filePath` | Blocking hook failure will **reject the tool execution**; the error is returned to the model |
| **`on_tool_complete`** | After a local/MCP tool has been physically executed **successfully** | `toolCallId`, `toolName`, `toolArgs`, `filePath`, `toolResult` | Blocking hook failure will **rewrite the tool result**, triggering model self-repair in the next round |
| **`on_loop_end`** | When a single reasoning loop finishes (AI wrap-up without tool calls; triggered on success, exception, or interruption) | Session basic info only (`cid` etc., no tool data) | Failures are logged only, no impact on the main flow |

> [!TIP]
> Tool-related events (`on_tool_call` / `on_tool_complete`) carry the full tool invocation context, making them the primary choices for **argument validation, code style checks, and audit notifications**. Note that `on_tool_complete` only triggers after successful tool execution; failed or permission-blocked tools will not trigger it.

---

## ⚙️ 3. `hooks.json` Specification

The configuration is an array of Hook rules, each defining trigger events, filter conditions, and command arguments:

```json
[
  {
    "name": "check-java-formatting",
    "event": "on_tool_call",
    "toolFilter": "replace_file_content",
    "pattern": "*.java",
    "action": "execute_command",
    "blocking": true,
    "args": {
      "command": "python scripts/validate_java.py ${path}"
    }
  },
  {
    "name": "audit-log-notifier",
    "event": "on_tool_complete",
    "action": "execute_command",
    "blocking": false,
    "args": {
      "command": "node scripts/audit_notify.js"
    }
  }
]
```

### 📋 Field Specification

| Field Name | Type | Description |
| :--- | :--- | :--- |
| **`name`** | String | Unique name of the hook rule (Required) |
| **`event`** | String | Trigger event point: `on_context_start` / `on_loop_start` / `on_tool_call` / `on_tool_complete` / `on_loop_end` |
| **`toolFilter`** | String | Specific tool name to trigger (e.g. `replace_file_content`; leave empty for all tools) |
| **`pattern`** | String | File path Glob pattern matching (e.g. `*.java`, case-insensitive full-path match; leave empty for no filtering) |
| **`action`** | String | Action type when matched, currently fixed to `execute_command` |
| **`blocking`** | Boolean | Whether to block execution: `true` for synchronous blocking (failure interrupts related flow); `false` for async non-blocking |
| **`args.command`** | String | Shell/CMD command to execute, supporting `${path}` macro replacement |

### 🔍 Filter Evaluation Order

1. Match by `event` first; skip if mismatched;
2. Then match by `toolFilter` (skipped if configured but no tool name is present);
3. Finally match by `pattern` (skipped if configured but no file path is present);
4. Execute the command only when all conditions hit.

---

## 🚀 4. Execution Engine & Data Passing

Backend `HookEngineServiceImpl` provides the following features during Hook execution:

1. **Macro Replacement**: Replaces `${path}` in commands with the normalized **absolute path** of the target file (relative paths are converted automatically).
2. **Platform-adaptive Execution**: Commands run via `cmd.exe /c` on Windows and `sh -c` on Linux/Mac; the subprocess working directory is automatically set to the project root bound to the current session.
3. **Context Data Passing**: Writes a snapshot of the runtime context to a temporary JSON file before spawning the subprocess, passed via environment variable:
   ```bash
   # Environment Variable (points to a temporary JSON file, auto-cleaned after the process ends)
   ST_CUTE_HOOK_DATA_PATH=/tmp/st-cute_hook_{ruleName}_xxx.json
   ```
   Scripts can read this file path from the environment variable to access the full invocation context.
4. **Timeout & Security Control**:
   - Strict 60-second timeout to forcibly destroy hanging subprocesses and prevent deadlocks;
   - In blocking mode (`blocking: true`), a non-zero process exit code throws an exception and triggers the corresponding blocking behavior;
   - Execution status (`running`, `success`, `failed`) is recorded in backend logs for troubleshooting.

### 📦 Context JSON Field Reference

The JSON file referenced by `ST_CUTE_HOOK_DATA_PATH` contains the following fields:

| Field Name | Type | Description |
| :--- | :--- | :--- |
| **`cid`** | Long | Current session unique ID |
| **`hookName`** | String | Matched hook rule name |
| **`event`** | String | Triggered event name |
| **`blocking`** | Boolean | Whether the rule is in blocking mode |
| **`toolCallId`** | String | Tool call ID (null for non-tool events) |
| **`toolName`** | String | Name of the invoked tool |
| **`filePath`** | String | File path extracted from tool arguments |
| **`toolArgs`** | String | JSON string of the tool arguments |
| **`toolResult`** | String | Tool execution result text (only carried by `on_tool_complete`) |
| **`canceled`** | Boolean | Whether the user has requested interruption |
| **`loopRunning`** | Boolean | Whether the reasoning loop is running |
| **`activeAssistantMsgId`** | Long | Current active ASSISTANT message ID |
| **`inputTokens`** | Long | Cumulative input token count |
| **`outputTokens`** | Long | Cumulative output token count |
| **`cachedTokens`** | Long | Cumulative cache-hit token count |
| **`iterationCount`** | Integer | Current ReAct iteration count |
| **`parentCid`** | Long | Parent session ID (non-null in SubAgent scenarios) |
| **`permissionMode`** | String | Current permission mode (e.g. `READ_ONLY` / `SMART_APPROVAL`) |
| **`providerGroup`** | String | Current model provider group |
| **`providerModelName`** | String | Current model name |
| **`worktreePath`** | String | Git WorkTree isolated path (null if not enabled) |
| **`worktreeBranch`** | String | Git WorkTree branch name (null if not enabled) |
| **`callToolCount`** | Integer | Cumulative tool call count |
| **`consecutiveUnknownTools`** | Integer | Consecutive unknown tool call count (meltdown protection counter) |

### 🛑 Blocking Behavior Details

* **`on_tool_call` Blocking**: When a blocking hook fails, the tool is **rejected outright** (never reaches permission evaluation or physical execution); the failure status and error are persisted and returned to the model;
* **`on_tool_complete` Blocking**: The tool has already been physically executed; if a blocking hook fails, the real tool result is **rewritten as an error message** (with hook error details), guiding the model to self-repair in the next round;
* **`on_context_start` / `on_loop_start` / `on_loop_end` Blocking**: These three points are inside the reasoning main loop; hook failures are logged as warnings only and **never interrupt** the agent main flow, keeping the core reasoning chain stable.
