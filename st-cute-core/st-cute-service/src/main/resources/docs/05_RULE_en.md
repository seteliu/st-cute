# RULE Configuration Guide

[简体中文](./05_RULE.md) | [English](./05_RULE_en.md)

ST-Cute supports injecting project-specific development rules, coding standards, and behavioral constraints into the AI Agent via the `AGENTS.md` master convention and the `rules/` sub-directory rule sets, helping LLMs precisely understand code style and project conventions.

---

## 🎯 Purpose & Mechanism

RULE is the core rules mechanism of the agent prompt system. Before each reasoning cycle (ReAct Loop) begins, the backend reads and assembles rules according to file contracts:

* **Global Rules**: `~/.st-cute/AGENTS.md` and `~/.st-cute/rules/` (General rules applicable to all projects)
* **Project-level Master Convention**: `{projectBasePath}/.st-cute/AGENTS.md` (Project-specific rules, highest priority)
* **Project Common Master Convention**: `{projectBasePath}/.agents/AGENTS.md` (Project-shared rules, suitable for Git versioning and team consistency)
* **Project Rule Sets**: `{projectBasePath}/.st-cute/rules/` (project level) and `{projectBasePath}/.agents/rules/` (project common level) (one file per rule, nested sub-directories supported for topic-based partitioning)
* **Assembly Order**: Injected by level priority — Project-level → Project Common → Global (master convention first, then sub-directory rule sets; sorted by file name within the same directory, so loading order is stable and predictable)
* **Effective Semantics**: Stacking contract — rules from all three levels take effect; precedence: explicit chat prompt > project-level rules > project common rules > global rules

---

## 📁 1. Configuration Files & Paths

| Level | Path | Description |
| --- | --- | --- |
| Global | `~/.st-cute/AGENTS.md` | General rules for all projects |
| Global rule sets | `~/.st-cute/rules/*.md` | One file per rule, nested sub-directories and `enable` toggle supported |
| Project-level master convention | `{projectBasePath}/.st-cute/AGENTS.md` | Project-specific rules (highest priority) |
| Project common master convention | `{projectBasePath}/.agents/AGENTS.md` | Project-shared rules (suitable for Git versioning and team consistency) |
| Project-level rule sets | `{projectBasePath}/.st-cute/rules/*.md` | One file per rule, nested sub-directories supported |
| Project common rule sets | `{projectBasePath}/.agents/rules/*.md` | One file per rule, nested sub-directories supported |

> Rules from all three levels **stack together**; injection order: Project-level → Project Common → Global (higher priority is placed first in the prompt).

---

## 🗂️ 2. `rules/` Sub-directory Rule Sets

When rules grow large, you can split them into separate files under the `rules/` sub-directory for partitioned management. The `rules/` mechanism applies to the global level (`~/.st-cute/rules/`) as well as both project levels (`.st-cute/rules/`, `.agents/rules/`):

* **One file per rule**: Each `.md` file under `rules/` is treated as an independent rule; the file content is the rule body
* **Recursive nested sub-directories**: Multi-level sub-directories are supported for topic partitioning (e.g., `rules/backend/`, `rules/frontend/`); the system scans level by level
* **Stable loading order**: Files within the same directory are loaded in ascending file-name order, ensuring predictable prompt injection order
* **Traceable origin**: The nesting level is reflected in the rule name (e.g., `rules/backend/api.md` is identified as `backend/api.md`), so both the WebUI display and the LLM prompt can trace the source

### Directory Structure Example

```
.agents/
├── AGENTS.md              # Project master convention
└── rules/
    ├── backend.md         # Project rule (backend.md)
    ├── frontend.md        # Project rule (frontend.md)
    ├── backend/
    │   ├── api.md         # Project rule (backend/api.md)
    │   └── db.md          # Project rule (backend/db.md)
    └── frontend/
        └── style.md       # Project rule (frontend/style.md)
```

### 🔧 Rule Enable/Disable Switch

A rule file can be enabled or disabled by declaring an `enable` metadata line in its body (maintained manually by the user):

```markdown
enable: false

# This rule is disabled
- Content here will not be injected into the prompt
```

Matching rules:

* Only a **full-line** `enable: true/false` declaration is recognized (e.g., `enable: true`)
* Case-insensitive, leading indentation tolerated
* If the line is missing or malformed, the rule is **treated as enabled by default**
* The first matching declaration in the file takes effect

---

## 📝 3. `AGENTS.md` Example

You can define requirements by module in `AGENTS.md` using standard Markdown:

```markdown
# st-cute Project Development Conventions

- Backend Rules (Java)
    - Do not use fully qualified class names in code; always use standard imports
    - Use Lombok to simplify POJOs and service classes
    - Keep comments clear and maintain existing docstrings
    - Do not use single-element arrays to workaround variable mutation in lambdas

- Frontend Rules (Vue 3)
    - Use TypeScript + Composition API `<script setup>` syntax
    - Prefer Naive UI component library

- Interaction & Safety Rules
    - Do not directly modify core logic without prior user discussion
    - Strictly adhere to permission modes (Read-Only / Smart Approval)
```

Rule files under the `rules/` sub-directory are written the same way, with no extra format required (the `enable` metadata line is optional).

---

## 💡 Best Practices

1. **Concise master convention, partitioned details**: Keep global, high-priority conventions in `AGENTS.md`; move stack- or module-specific details into the `rules/` sub-directory, naming files and sub-directories by topic (e.g., `rules/java/style.md`).
2. **Keep Rules Concise**: Avoid pasting lengthy business documents; use clean, structured bullet lists.
3. **Explicit Negative Constraints**: Use clear explicit prefixes for forbidden actions (e.g., "Do not auto compile", "Do not use fully qualified names"), which LLMs follow much more effectively.
4. **Disable temporarily via switch**: For rules not intended for long-term use, add `enable: false` at the top of the file to disable it, without deleting the content.
5. **Team Consistency**: Commit both `.agents/AGENTS.md` and `.agents/rules/` to your Git repository to ensure consistent agent behavior across all team members.
