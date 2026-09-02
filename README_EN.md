<p align="center">
  <img src="st-cute-web/public/favicon.svg" width="96" height="96" alt="ST-Cute Logo" />
</p>

<h1 align="center">ST-Cute</h1>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README_EN.md">English</a>
</p>

**ST-Cute** is a decoupled front-end and back-end AI Coding Agent & Harness, shipping as both a desktop client and a web application.
Built on an event-driven ReAct Loop, it supports RULE, SKILL, MCP, and HOOK, and is compatible with mainstream `.agents` directory configurations.
It offers full observability into LLM HTTP requests and the tool call process.
Between overly simple tools and bloated frameworks, it strikes the perfect balance as a lightweight player that is just right—easy to handle.

[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3.x-brightgreen.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 📸 Interface Preview

| 📱 Mobile Adaptation (Vibe Coding on Mobile) | 💻 PC Desktop Panoramic Experience |
| :---: | :---: |
| ![Mobile Preview](.github/assets/mobile-preview.jpg) | ![Desktop Preview](.github/assets/desktop-preview.jpg) |

---

## 🎯 Target Audience
* 💡 **Want to understand Coding Agent principles**: Clean code structure, small but complete, well-designed without over-engineering—an excellent Java-based Agent reference.
* 🔍 **Pursue full control & complete observability**: Clearly monitor thinking process, tool call chains, SubAgent execution status, active subprocesses, and complete LLM HTTP request/response logs.
* 📱 **Deploy a cross-device Agent**: B/S architecture with responsive WebUI; PC and mobile share the same origin—"Deploy once, connect from multiple devices".
* 🛡️ **Pure & secure**: 100% portable, no backdoors, with path sandbox support.

---

## 🛠️ Tech Stack

| Module | Technology | Description |
| :--- | :--- | :--- |
| **Backend** (`st-cute-service`) | Java 25 / Spring Boot 4.1 | Event-driven lightweight architecture |
| **Persistence** | SQLite 3 + MyBatis-Flex | WAL mode enabled by default, no heavy database setup |
| **Communication** | OkHttp + WebSocket | REST API + real-time bi-directional streaming, complete HTTP probe logs |
| **Frontend** (`st-cute-web`) | Vue 3 + Vite 8 + TypeScript 6 | Naive UI component library, managed via pnpm workspace |
| **Desktop Shell** (`st-cute-desktop`) | Rust + Tauri | Native window shell, double-click to run, auto-manages backend lifecycle |

---

## ✨ Feature Overview

- **Implemented**
    - ReAct Loop
    - Tool Executions
        - File search, read & write
        - Command execution
        - Git WorkTree
    - SKILL
    - MCP
    - HOOK
    - RULE (`AGENTS.md`)
    - SubAgent
    - Permission Control
        - Read-Only
        - Smart Approval
        - Path Sandbox
    - Custom Model Providers
        - Supports OpenAI Chat, OpenAI Response, and Anthropic Claude protocols
        - Configurable full URL (e.g., when the endpoint is not /chat/completions)
        - Configurable context window size
        - Configurable max tokens per response (`max_tokens`)
        - Configurable reasoning effort
        - Configurable temperature
    - Multimodal
        - Images
        - Built-in tools with native support for PDF / Word / Excel / PPT
    - Experience Enhancements
        - Custom Enter/line-break key
        - Message aggregation display toggle
        - Path sandbox toggle
        - Complete LLM HTTP logging toggle
        - Access security code
    - Multi-Device Support
        - Desktop client (Rust shell + bundled JRE, out-of-the-box)
        - Responsive web UI with mobile adaptation
    - Multi-language
        - Frontend supports both Chinese and English
- **Not yet released, planned**
    - Online search
    - Multi-language support for backend response messages and LLM built-in prompts
    - Multiple theme colors

---

## 🚀 Quick Start

This document covers downloading and running pre-compiled packages, security access configuration, and local source code development and building.

---

### 📦 Run Pre-compiled Packages Directly (Recommended)

You can download the archive for your system directly from the GitHub **[Releases](releases)** page and run it out of the box.

| Platform / Package | Includes | Launch Method |
|:---| :--- |:---|
| **`st-cute-desktop-win-x64-x.x.x.zip`** | `st-cute.exe` desktop shell + `resources/` (containing `app.jar` + trimmed JRE) | Unzip and double-click **`st-cute.exe`** |
| **`st-cute-bundle-win-x64-x.x.x.zip`** | `app.jar` + trimmed JRE + `st-cute.cmd` console script | Unzip and double-click **`st-cute.cmd`** |
| **`st-cute-bundle-linux-x64-x.x.x.tar.gz`** | `app.jar` + trimmed JRE + `st-cute.sh` | Unzip and run **`./st-cute.sh`** in a terminal |
| **`st-cute-bundle-mac-arm64-x.x.x.tar.gz`** | `app.jar` + trimmed JRE (Apple Silicon, M series) + `st-cute.sh` / `st-cute.command` | Unzip and double-click **`st-cute.command`** (or run `./st-cute.sh` in a terminal) |
| **`st-cute-bundle-mac-x64-x.x.x.tar.gz`** | `app.jar` + trimmed JRE (Intel) + `st-cute.sh` / `st-cute.command` | Unzip and double-click **`st-cute.command`** (or run `./st-cute.sh` in a terminal) |
| **`st-cute-base-x.x.x.zip`** | Only `app.jar` (no bundled JRE or launcher scripts) | Bring your own Java 25+, run **`java -jar app.jar`** |

#### 🧩 How It Works?

The core of ST-Cute is a **Java backend service**, and Java programs rely on a **JRE** (Java Runtime Environment) to run.
- The `base` package does not include a JRE and contains only the JAR (suitable for those who already have a Java environment)
- The `bundle` package bundles a JRE—one-click run in terminal, access via web browser
- The `desktop` package bundles a desktop app shell—one-click run for direct use, also accessible via web browser

> [!TIP]
> **Mac first-launch prompt "Apple cannot verify / blocked" workaround (one-time setup only)**:
> 1. When blocked on first double-click, click 【Done】 to close the dialog;
> 2. Open Mac **【System Settings】 ➔ 【Privacy & Security】**;
> 3. Scroll down to the **"Security"** section, click **【Open Anyway】** and enter your password;
> 4. Afterwards, simply double-click **`st-cute.command`** to run smoothly!

#### 🌐 Web Access URL
👉 **`http://localhost:9661`**

---

### ⚠️ Security Access & Public/Mobile Mapping (Must Read)

> [!WARNING]
> **Security Notice**:
> If you plan to map ports to the **public Internet**, or connect from **mobile devices (phones/tablets on external networks)** to your deployed ST-Cute:
> 1. After the service starts, be sure to configure the **Access Security Code** in the **【Settings】** page first;
> 2. Only map port `9661` to outside your LAN or the public network after the access code takes effect. Never expose an unprotected service directly to the public Internet!

---

### 💻 Source Code Development & Debugging (Developer Mode)

If you want to do secondary development or build from source:

#### Development Environment Requirements
* **Java JDK**: `Java 25` or higher
* **Build Tool**: `Maven 3.9+`
* **Node.js**: `Node.js 22+` & `pnpm 11+`

#### Start Backend Service (`st-cute-service`)
```bash
cd st-cute-service
mvn clean spring-boot:run
```
* In dev debug mode, the backend runs at `http://localhost:9661`.
* **Database**: On first run, `st-cute.db` (SQLite WAL mode) is auto-generated under the user home directory `.st-cute/` folder.

#### Start Frontend Service (`st-cute-web`)
```bash
cd st-cute-web
pnpm install
pnpm dev
```
* In dev debug mode, the frontend runs at `http://localhost:9662`.

#### Desktop Shell (`st-cute-desktop`) (Optional)

Daily development only requires starting the frontend and backend services and accessing via browser; this module only matters when developing or debugging the Rust desktop shell itself:
* **Tech Stack**: Rust + Tauri 2, providing the native window shell and backend lifecycle management
* **Environment Requirements**: Rust toolchain (`cargo`, MSVC target on Windows)
* **Runtime Dependencies**: The shell loads backend artifacts (`app.jar` + JRE); for local debugging, place them under the `st-cute-desktop/src-tauri/resources` directory first

---

## 📖 Documentation Guide

Modular documentation is available under the `st-cute-service/src/main/resources/docs/` directory. Click the links below for quick reference:

* 🚀 **[Quick Start](#-quick-start)**: Package download, environment requirements, and deployment.
* 📋 **[File Conventions](st-cute-service/src/main/resources/docs/02_file_conventions_en.md)**: Files referenced and produced at runtime.
* ⚙️ **[RULE Configuration](st-cute-service/src/main/resources/docs/05_RULE_en.md)**: `AGENTS.md` rule definitions and agent behavior constraints.
* 🧩 **[SKILL Extension Guide](st-cute-service/src/main/resources/docs/06_SKILL_en.md)**: Custom skill declarations and the loading mechanism.
* 🔌 **[MCP Protocol Integration](st-cute-service/src/main/resources/docs/07_MCP_en.md)**: Model Context Protocol (MCP) server configuration and tool mapping.
* ⚓ **[HOOK Mechanism](st-cute-service/src/main/resources/docs/08_HOOK_en.md)**: Lifecycle interception before and after tool calls.

---

## Why This Project?
It started as a hands-on journey during the AI wave to experience "how a Coding Agent is built from 0 to 1", so I wrote one myself.
Meanwhile, other Agents more or less had unsatisfying points:
- Observability: For example, I wanted to clearly monitor tool calls and complete HTTP logs.
- Multi-device: For example, I wanted to deploy it at home, connect from my phone when going out, and Vibe Coding on the subway.

Then I realized I could go one step further—it's not just a toy anymore, it's a practical tool.

---

## 📄 License

This project is open-sourced under the [MIT License](LICENSE); feel free to use, modify, and distribute.
