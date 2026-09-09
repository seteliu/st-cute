# RULE 规则配置指南

[简体中文](./05_RULE.md) | [English](./05_RULE_en.md)

ST-Cute 支持通过 `AGENTS.md` 主规约与 `rules/` 子目录规则集向智能体注入项目专有的开发规则、编码规范与行为约束，帮助大模型精准理解项目的代码风格与开发规约。

---

## 🎯 作用与机制

RULE 是智能体 Prompt 系统的核心规则文件。在每一次思考循环（ReAct Loop）开始前，系统会根据文件契约自动读取并拼装规则：

* **全局级规则**：`~/.st-cute/AGENTS.md` 与 `~/.st-cute/rules/`（适用于开发者所有项目的通用规范）
* **项目级主规约**：`{projectBasePath}/.st-cute/AGENTS.md`（项目专属规范，优先级最高）
* **项目通用级主规约**：`{projectBasePath}/.agents/AGENTS.md`（项目共享规范，适合提交 Git 团队统一）
* **项目级规则集**：`{projectBasePath}/.st-cute/rules/`；**项目通用级规则集**：`{projectBasePath}/.agents/rules/`（一文件一规则，支持嵌套子目录分区管理）
* **拼装顺序**：按层级优先级注入——项目级 → 项目通用级 → 全局级（主规约在前、子目录规则集在后，同级内按文件名排序，装载顺序稳定可预期）
* **生效语义**：叠加型契约，三层规则全部生效；优先级顺序：当前对话显式指令 > 项目级规则 > 项目通用级规则 > 全局级规则

---

## 📁 1. 配置文件与路径

| 层级 | 路径 | 说明 |
| --- | --- | --- |
| 全局级 | `~/.st-cute/AGENTS.md` | 开发者所有项目通用规范 |
| 全局级规则集 | `~/.st-cute/rules/*.md` | 一文件一规则，支持嵌套子目录与 `enable` 开关 |
| 项目级主规约 | `{projectBasePath}/.st-cute/AGENTS.md` | 项目专属规范（优先级最高） |
| 项目通用级主规约 | `{projectBasePath}/.agents/AGENTS.md` | 项目共享规范（适合提交 Git 团队统一） |
| 项目级规则集 | `{projectBasePath}/.st-cute/rules/*.md` | 一文件一规则，支持嵌套子目录 |
| 项目通用级规则集 | `{projectBasePath}/.agents/rules/*.md` | 一文件一规则，支持嵌套子目录 |

> 三个层级的规则**叠加生效**，注入顺序：项目级 → 项目通用级 → 全局级（优先级高者排在提示词前面）。

---

## 🗂️ 2. `rules/` 子目录规则集

当规则较多时，可将规则拆分为独立文件放入 `rules/` 子目录，实现分区管理。`rules/` 机制同时适用于全局级（`~/.st-cute/rules/`）与项目两级（`.st-cute/rules/`、`.agents/rules/`）：

* **一文件一规则**：`rules/` 目录下每个 `.md` 文件视为一条独立规则，文件内容即规则正文
* **递归嵌套子目录**：支持多级子目录按主题分区（如 `rules/backend/`、`rules/frontend/`），系统会逐层递归扫描
* **装载顺序稳定**：同一目录内按文件名升序排序装载，保证注入提示词的顺序可预期
* **来源可辨识**：嵌套层级会体现在规则名中（如 `rules/backend/api.md` 会以 `backend/api.md` 标识），前端展示与大模型提示词均可辨识来源

### 目录结构示例

```
.agents/
├── AGENTS.md              # 项目级主规约
└── rules/
    ├── backend.md         # 项目规则 (backend.md)
    ├── frontend.md        # 项目规则 (frontend.md)
    ├── backend/
    │   ├── api.md         # 项目规则 (backend/api.md)
    │   └── db.md          # 项目规则 (backend/db.md)
    └── frontend/
        └── style.md       # 项目规则 (frontend/style.md)
```

### 🔧 规则启停开关

规则文件可通过在正文中声明 `enable` 元数据行来控制启停（由用户手动编辑维护）：

```markdown
enable: false

# 本规则已停用
- 这里的内容不会被注入提示词
```

匹配规则说明：

* 仅识别**整行形式**的 `enable: true/false`（如 `enable: true`）
* 大小写不敏感、容忍行首缩进
* 未声明该行或格式非法时，**默认视为启用**
* 取文件中第一处匹配的声明

---

## 📝 3. `AGENTS.md` 编写示例

您可以像编写 Markdown 一样，在 `AGENTS.md` 中按模块列出开发要求：

```markdown
# st-cute 项目开发规约

- 后端规约 (Java)
    - 禁止在代码里使用全限定名，必须采用 import 方式引入
    - 统一使用 Lombok 简化 POJO 与服务类
    - 注释统一使用中文注释
    - 因 Lambda 导致的变量不能更新问题，禁止用单元素数组规避

- 前端规约 (Vue3)
    - 统一使用 TypeScript + Composition API `<script setup>` 语法
    - UI 组件库优先使用 Naive UI

- 交互与安全规约
    - 如果没有用户显式授权或要求，禁止直接修改主代码，先与用户讨论方案
    - 严格遵循只读/智能审批隔离
```

`rules/` 子目录下的规则文件编写方式相同，无需任何额外格式要求（`enable` 元数据行可选）。

---

## 💡 最佳实践建议

1. **主规约精简、细则分区**：`AGENTS.md` 保留全局性、高权重的规约；具体到技术栈或模块的细则拆分到 `rules/` 子目录，按主题命名文件与子目录（如 `rules/java/style.md`）。
2. **保持规则精简**：避免写入冗长的业务文档，尽量使用条理清晰的无序列表。
3. **明确负面约束**：对明确禁止的行为（如“禁止自动编译”、“禁止全限定名”）添加显式前缀，大模型遵循效果更佳。
4. **临时下线用开关**：不打算长期生效的规则，在文件头部加 `enable: false` 即可停用，无需删除文件内容。
5. **团队统一**：可将 `.agents/AGENTS.md` 与 `.agents/rules/` 一并提交至 Git 仓库，实现团队全员 Coding Agent 行为规约的一致性。
