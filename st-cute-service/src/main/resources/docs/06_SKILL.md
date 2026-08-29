# SKILL 扩展指南

[简体中文](./06_SKILL.md) | [English](./06_SKILL_en.md)

SKILL（技能扩展体系）允许开发者为 ST-Cute 引入领域专属的能力包、工作流模版及自动化 Prompt 脚本，让智能体快速具备特定领域的专家能力。

---

## 🧩 1. SKILL 目录结构与位置

技能包以独立子目录的形式组织，支持全局与项目级配置：

* **全局技能目录**：`~/.st-cute/skills/`
* **项目技能目录**：`{projectBasePath}/.agents/skills/` 或 `{projectBasePath}/.st-cute/skills/`

```text
skills/
├── my-refactor-skill/       # 技能包独立文件夹
│   ├── SKILL.md            # 核心技能定义文件（必须）
│   ├── scripts/            # 辅助脚本工具（可选）
│   └── templates/          # 代码模版文件（可选）
```

---

## 📝 2. `SKILL.md` 规范与结构

`SKILL.md` 采用标准的 **YAML Frontmatter + Markdown** 组合格式：

```markdown
---
name: code-refactor
description: "专用于对旧版 Java/Spring 代码进行现代化重构的技能包"
command: "refactor"
mode: "inline"
tools:
  - "view_file"
  - "replace_file_content"
---

# 技能指导说明与 Prompt

当执行代码重构时，请严格遵循以下步骤：
1. 检查代码中的全限定类名，重构为标准 import；
2. 将传统 Getter/Setter 替换为 Lombok 注解；
3. 检查是否有未处理的资源流，重构为 try-with-resources 形式。
```

### 📋 字段说明

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| **`name`** | String | 技能唯一标识符（必须） |
| **`description`** | String | 技能简短描述，帮助 Agent 判断触发时机 |
| **`command`** | String | 对应的 Slash 命令触发词（如 `/refactor`） |
| **`mode`** | String | 执行模式：`inline`（注入当前会话上下文执行）或 `fork`（派生独立子智能体执行） |
| **`tools`** | List | 技能可调用的限定工具白名单（可选） |

---

## ⚡ 3. 动态热扫描与热重载

1. **热扫描**：后端 `SkillManagerServiceImpl` 会自动扫描全局与项目级目录。若存在同名技能，项目级技能将自动覆盖全局技能。
2. **上下文绑定**：每个会话根据其绑定的项目工作区装载专属技能列表。
3. **Prompt 在线编辑与热更新**：支持在运行期通过系统在线编辑技能 Prompt：自动保留 YAML Frontmatter 元数据，仅覆写正文并写盘，同步刷新内存缓存。
4. **重载时机**：全局技能在首次访问时扫描并缓存，项目级技能在会话启动时装载；直接修改磁盘上的 `SKILL.md` 后，需在重新装载（如新会话启动）后才会生效。
