# HOOK 钩子机制指南

[简体中文](./08_HOOK.md) | [English](./08_HOOK_en.md)

HOOK 机制提供了对智能体执行生命周期的切面拦截能力。通过配置 `hooks.json`，开发者能够在工具调用前、工具完成后以及大模型 HTTP 请求前后插入自定义审计、参数校验、文件过滤或自动化脚本。

---

## ⚓ 1. 配置文件与路径

* **全局 Hook 配置**：`~/.st-cute/hooks.json`
* **项目 Hook 配置**：`{projectBasePath}/.agents/hooks.json` 或 `{projectBasePath}/.st-cute/hooks.json`

---

## ⚙️ 2. `hooks.json` 配置规范

配置包含一个 Hook 规则数组，每个规则定义了触发事件、过滤条件与命令参数：

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

### 📋 字段与参数说明

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| **`name`** | String | 钩子规则名称（必须） |
| **`event`** | String | 触发事件切面节点（如 `beforeToolExecution` / `afterToolExecution`） |
| **`toolFilter`** | String | 限定触发的工具名称（如 `replace_file_content`，留空代表匹配所有工具） |
| **`pattern`** | String | 文件路径 Glob 通配符匹配（如 `*.java` 或 `src/**`） |
| **`isBlocking`** | Boolean | 是否阻断执行：`true` 为强阻断同步等待；`false` 为异步非阻断 |
| **`args.command`** | String | 触发出发的 Shell/CMD 脚本命令，支持 `${path}` 宏替换 |

---

## 🚀 3. 执行引擎与数据传递机制

后端 `HookEngineServiceImpl` 在执行 Hook 脚本时具备以下特性：

1. **宏替换**：自动将命令中的 `${path}` 变量替换为当前操作文件的规范化绝对路径。
2. **上下文数据传递**：在启动子进程前，引擎会将当前的运行期上下文（`HookContext`，包含会话 ID、工具名、参数及文件路径）写入临时 JSON 文件，并通过系统环境变量传递给子进程：
   ```bash
   # 环境变量名
   ST_CUTE_HOOK_DATA_PATH=/tmp/st-cute_hook_ruleName_xxx.json
   ```
   脚本中可直接读取该环境变量指向的文件以获取完整的调用上下文。
3. **超时与安全管控**：
   - 强超时控制（默认 **60 秒**），超过 60 秒强制杀掉子进程，避免任务死锁；
   - 强阻断模式下若进程退出码不为 `0`，系统将抛出异常并阻止工具执行；
   - 执行状态（`running`、`success`、`failed`）通过 WebSocket 实时推送到前端看板。
