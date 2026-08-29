# RULE 规则配置指南

[简体中文](./05_RULE.md) | [English](./05_RULE_en.md)

ST-Cute 支持通过 `AGENTS.md` 向智能体注入项目专有的开发规则、编码规范与行为约束，帮助大模型精准理解项目的代码风格与开发规约。

---

## 🎯 作用与机制

`AGENTS.md` 是智能体 Prompt 系统的核心规则文件。在每一次思考循环（ReAct Loop）开始前，系统会根据文件契约自动读取并拼装规则：

* **全局级规则**：`~/.st-cute/AGENTS.md`（适用于开发者所有项目的通用规范）
* **项目级规则**：`{projectBasePath}/.agents/AGENTS.md` 或 `{projectBasePath}/.st-cute/AGENTS.md`（项目专属规范；两个目录共存时会合并读取，`.agents` 在前）
* **拼装顺序**：系统提示词中先拼接全局规则，再拼接项目级规则
* **优先级顺序**：当前对话显式指令 > 项目级 `AGENTS.md` > 全局级 `AGENTS.md`

---

## 📝 `AGENTS.md` 编写示例

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

---

## 💡 最佳实践建议

1. **保持规则精简**：避免写入冗长的业务文档，尽量使用条理清晰的无序列表。
2. **明确负面约束**：对明确禁止的行为（如“禁止自动编译”、“禁止全限定名”）添加显式前缀，大模型遵循效果更佳。
3. **团队统一**：可将 `.agents/AGENTS.md` 提交至 Git 仓库，实现团队全员 Coding Agent 行为规约的一致性。
