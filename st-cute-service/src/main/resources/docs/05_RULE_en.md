# RULE Configuration Guide

[简体中文](./05_RULE.md) | [English](./05_RULE_en.md)

ST-Cute supports injecting project-specific development rules, coding standards, and behavioral constraints into the AI Agent via `AGENTS.md`, helping LLMs precisely understand code style and project conventions.

---

## 🎯 Purpose & Mechanism

`AGENTS.md` is the core rules document of the agent prompt system. Before each reasoning cycle (ReAct Loop) begins, the backend reads and assembles rules according to file contracts:

* **Global Rules**: `~/.st-cute/AGENTS.md` (General rules applicable to all projects)
* **Project Rules**: `{projectBasePath}/.agents/AGENTS.md` or `{projectBasePath}/.st-cute/AGENTS.md` (Project-specific rules; when both directories co-exist they are merged, with `.agents` read first)
* **Assembly Order**: Global rules are concatenated into the system prompt first, followed by project-level rules
* **Precedence Order**: Explicit chat prompt > Project-level `AGENTS.md` > Global `AGENTS.md`

---

## 📝 `AGENTS.md` Example

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

---

## 💡 Best Practices

1. **Keep Rules Concise**: Avoid pasting lengthy business documents; use clean, structured bullet lists.
2. **Explicit Negative Constraints**: Use clear explicit prefixes for forbidden actions (e.g., "Do not auto compile", "Do not use fully qualified names"), which LLMs follow much more effectively.
3. **Team Consistency**: Commit `.agents/AGENTS.md` to your Git repository to ensure consistent agent behavior across all team members.
