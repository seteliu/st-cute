# SKILL Extension Guide

[简体中文](./06_SKILL.md) | [English](./06_SKILL_en.md)

The SKILL framework allows developers to introduce domain-specific capability packages, workflow templates, and automated prompt scripts into ST-Cute, giving agents expert capabilities in specific domains.

---

## 🧩 1. Directory Structure & Locations

Skill packages are organized as independent subdirectories, supporting both global and project-level configurations:

* **Global Skills Directory**: `~/.st-cute/skills/`
* **Project Skills Directory**: `{projectBasePath}/.agents/skills/` or `{projectBasePath}/.st-cute/skills/`

```text
skills/
├── my-refactor-skill/       # Skill folder
│   ├── SKILL.md            # Core skill definition file (Required)
│   ├── scripts/            # Helper scripts & tools (Optional)
│   └── templates/          # Code template files (Optional)
```

---

## 📝 2. `SKILL.md` Format & Specification

`SKILL.md` uses standard **YAML Frontmatter + Markdown** format:

```markdown
---
name: code-refactor
description: "Skill package dedicated to modernizing legacy Java/Spring codebases"
command: "refactor"
mode: "inline"
tools:
  - "view_file"
  - "replace_file_content"
---

# Skill Instructions & Prompts

When performing code refactoring, strictly follow these steps:
1. Inspect fully qualified class names and refactor to standard imports;
2. Replace boilerplate getters/setters with Lombok annotations;
3. Refactor resource streams to try-with-resources blocks.
```

### 📋 Field Specification

| Field Name | Type | Description |
| :--- | :--- | :--- |
| **`name`** | String | Unique identifier for the skill (Required) |
| **`description`** | String | Short description helping the Agent decide when to trigger |
| **`command`** | String | Slash command trigger word (e.g. `/refactor`) |
| **`mode`** | String | Execution mode: `inline` (inject into current session context) or `fork` (spawn a dedicated subagent) |
| **`tools`** | List | Whitelisted tools allowed for this skill (Optional) |

---

## ⚡ 3. Hot Scanning & Dynamic Reloading

1. **Hot Scanning**: Backend `SkillManagerServiceImpl` automatically scans global and project directories. If duplicate skill names exist, project-level skills override global skills.
2. **Context Binding**: Each chat session loads its bound project workspace skill list.
3. **Prompt Online Editing & Hot Updating**: Supports editing skill prompts online at runtime: the YAML Frontmatter metadata is preserved automatically, only the body is overwritten and written to disk, and the memory cache is refreshed synchronously.
4. **Reload Timing**: Global skills are scanned and cached on first access; project-level skills are loaded at session startup. Direct edits to `SKILL.md` on disk take effect after re-loading (e.g. at the next session startup).
