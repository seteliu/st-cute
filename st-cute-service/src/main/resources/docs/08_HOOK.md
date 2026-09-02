# HOOK 钩子机制指南

[简体中文](./08_HOOK.md) | [English](./08_HOOK_en.md)

HOOK 机制提供了对智能体执行生命周期的切面拦截能力。通过配置 `hooks.json`，开发者能够在会话启动、单轮推理开始/结束、工具调用前、工具执行完毕等关键切面插入自定义审计、参数校验、文件过滤或自动化脚本。

---

## ⚓ 1. 配置文件与路径

* **全局 Hook 配置**：`~/.st-cute/hooks.json`
* **项目 Hook 配置**：`{projectBasePath}/.agents/hooks.json` 或 `{projectBasePath}/.st-cute/hooks.json`

全局规则对所有会话生效；项目级规则在会话启动时装载并与全局规则合并，同名规则互不冲突（项目规则以项目路径为命名空间）。

---

## 🎯 2. 生命周期事件总览

目前系统共支持 **5 个** Hook 事件切面，配置 `event` 字段时推荐使用小写下划线形式（事件名不区分大小写）：

| 事件名 | 触发时机 | 上下文可用数据 | 阻断效果 |
| :--- | :--- | :--- | :--- |
| **`on_context_start`** | 会话环境启动初始化时（ReAct 循环开始前） | 仅会话基础信息（`cid` 等，无工具数据） | 仅记录失败日志，不影响主流程 |
| **`on_loop_start`** | 智能体单次推理开始时 | 仅会话基础信息（`cid` 等，无工具数据） | 仅记录失败日志，不影响主流程 |
| **`on_tool_call`** | 智能体发起工具调用时（尚未物理执行，位于权限评估之前） | `toolCallId`、`toolName`、`toolArgs`、`filePath` | 阻断型 Hook 失败将**拒绝执行该工具**，错误信息返回给模型 |
| **`on_tool_complete`** | 本地/MCP 工具物理执行**成功后** | `toolCallId`、`toolName`、`toolArgs`、`filePath`、`toolResult` | 阻断型 Hook 失败将**改写工具返回结果**，触发模型下一轮自我修复 |
| **`on_loop_end`** | 智能体单次推理循环完结时（AI 收尾不再调用工具，正常/异常/中断均会触发） | 仅会话基础信息（`cid` 等，无工具数据） | 仅记录失败日志，不影响主流程 |

> [!TIP]
> 工具相关事件（`on_tool_call` / `on_tool_complete`）携带完整的工具调用上下文，是**参数校验、代码规约检查、审计通知**等场景的主力切面；`on_tool_complete` 仅在工具执行成功后触发，工具执行失败或被权限拦截时不会触发。

---

## ⚙️ 3. `hooks.json` 配置规范

配置为一个 Hook 规则数组，每个规则定义了触发事件、过滤条件与命令参数：

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

### 📋 字段与参数说明

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| **`name`** | String | 钩子规则名称，唯一标识（必须） |
| **`event`** | String | 触发事件切面节点：`on_context_start` / `on_loop_start` / `on_tool_call` / `on_tool_complete` / `on_loop_end` |
| **`toolFilter`** | String | 限定触发的工具名称（如 `replace_file_content`，留空代表匹配所有工具） |
| **`pattern`** | String | 文件路径 Glob 通配符匹配（如 `*.java`，对整个文件路径做不区分大小写的全匹配，留空代表不过滤） |
| **`action`** | String | 匹配命中后的动作方式，当前固定使用 `execute_command` |
| **`blocking`** | Boolean | 是否阻断执行：`true` 为强阻断同步等待（失败将中断相关流程）；`false` 为异步非阻断执行 |
| **`args.command`** | String | 触发执行的 Shell/CMD 脚本命令，支持 `${path}` 宏替换 |

### 🔍 过滤条件生效顺序

1. 先按 `event` 匹配事件类型，不匹配直接跳过；
2. 再按 `toolFilter` 匹配工具名（配置了但当前无工具名则跳过）；
3. 最后按 `pattern` 匹配文件路径（配置了但当前无文件路径则跳过）；
4. 全部命中后执行命令。

---

## 🚀 4. 执行引擎与数据传递机制

后端 `HookEngineServiceImpl` 在执行 Hook 脚本时具备以下特性：

1. **宏替换**：自动将命令中的 `${path}` 变量替换为当前操作文件的规范化**绝对路径**（相对路径会自动转换）。
2. **平台自适应执行**：Windows 下通过 `cmd.exe /c` 执行，Linux/Mac 下通过 `sh -c` 执行；子进程工作目录自动设置为当前会话绑定的项目根路径。
3. **上下文数据传递**：在启动子进程前，引擎会将当前运行期上下文的快照写入临时 JSON 文件，并通过系统环境变量传递给子进程：
   ```bash
   # 环境变量名（指向一个临时 JSON 文件，进程结束后自动清理）
   ST_CUTE_HOOK_DATA_PATH=/tmp/st-cute_hook_{规则名}_xxx.json
   ```
   脚本中可直接读取该环境变量指向的文件以获取完整的调用上下文。
4. **超时与安全管控**：
   - 强超时控制（默认 **60 秒**），超过 60 秒强制销毁子进程，避免任务死锁；
   - 强阻断模式（`blocking: true`）下若进程退出码不为 `0`，系统将抛出异常并触发对应的阻断行为；
   - 执行状态（`running`、`success`、`failed`）会记录到后端运行日志，便于排查。

### 📦 上下文 JSON 字段说明

环境变量 `ST_CUTE_HOOK_DATA_PATH` 指向的 JSON 文件包含以下字段：

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| **`cid`** | Long | 当前会话唯一 ID |
| **`hookName`** | String | 命中的钩子规则名称 |
| **`event`** | String | 触发的事件名 |
| **`blocking`** | Boolean | 该规则是否为阻断模式 |
| **`toolCallId`** | String | 工具调用 ID（非工具事件为 null） |
| **`toolName`** | String | 被调用的工具名称 |
| **`filePath`** | String | 工具入参中提取的文件路径 |
| **`toolArgs`** | String | 工具入参参数的 JSON 字符串 |
| **`toolResult`** | String | 工具执行生成的结果文本（仅 `on_tool_complete` 事件携带） |
| **`canceled`** | Boolean | 用户是否已请求中断执行 |
| **`loopRunning`** | Boolean | 推理循环是否运行中 |
| **`activeAssistantMsgId`** | Long | 当前活跃的 ASSISTANT 消息 ID |
| **`inputTokens`** | Long | 累计输入 Token 数 |
| **`outputTokens`** | Long | 累计输出 Token 数 |
| **`cachedTokens`** | Long | 累计缓存命中 Token 数 |
| **`loopCount`** | Integer | 当前循环轮次（第几轮），用户发消息置 1，每轮工具完成后由合法触发者推进 |
| **`parentCid`** | Long | 父会话 ID（SubAgent 场景下非空） |
| **`permissionMode`** | String | 当前权限模式（如 `READ_ONLY` / `SMART_APPROVAL`） |
| **`providerGroup`** | String | 当前使用的模型供应商分组 |
| **`providerModelName`** | String | 当前使用的模型名称 |
| **`worktreePath`** | String | Git WorkTree 隔离路径（未启用为 null） |
| **`worktreeBranch`** | String | Git WorkTree 分支名（未启用为 null） |
| **`callToolCount`** | Integer | 累计工具调用次数 |
| **`consecutiveUnknownTools`** | Integer | 连续未知工具调用次数（熔断保护计数） |

### 🛑 阻断行为细节

* **`on_tool_call` 阻断**：阻断型 Hook 执行失败时，该工具将被**直接拒绝执行**（不会进入权限评估与物理执行），失败状态与错误信息落库并返回给模型；
* **`on_tool_complete` 阻断**：工具已物理执行成功，若阻断型 Hook 执行失败，工具的真实返回结果会被**改写为错误提示**（附 Hook 报错详情），引导模型在下一轮根据报错信息自我修复；
* **`on_context_start` / `on_loop_start` / `on_loop_end` 阻断**：这三个切面位于推理主循环中，Hook 失败仅记录警告日志，**不会中断**智能体主流程，保证核心推理链路稳定。
