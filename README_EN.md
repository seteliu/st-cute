<p align="center">
  <img src="st-cute-web/public/favicon.svg" width="96" height="96" alt="ST-Cute Logo" />
</p>

<h1 align="center">ST-Cute</h1>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README_EN.md">English</a>
</p>

**ST-Cute** is a minimalist, user-friendly, feature-complete AI Coding Agent & Harness, shipping as both a desktop client and a web application.

[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3.x-brightgreen.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/seteliu/st-cute)](https://github.com/seteliu/st-cute/releases)
[![CI](https://github.com/seteliu/st-cute/actions/workflows/ci.yml/badge.svg)](https://github.com/seteliu/st-cute/actions/workflows/ci.yml)

---

## 🏛️ Project Highlights

**Usage & Experience**

- **Design philosophy**: design is a must, but over-designing is not. Just right between bare-bones and bloated. If AI is a wild horse, most of the time what you need is not a bulky aircraft carrier, but a simple, easy-to-use set of harness.
- **Multi-form packages**: multiple release package forms adapted to different environments — deployable on both desktops and servers, unzip and run.
- **Multi-device sync**: deploy once, and desktop / web / mobile clients all get the full feature set with responsive adaptation — Vibe Coding anywhere, even on your phone.
- **Office support**: PDF / Word / Excel read directly via built-in tools, no external conversion needed.
- **Great experience**:
  - Complete yet restrained built-in features that never weigh you down. Leave your creative stage to the componentized Harness.
  - Smart folding for noise reduction, ultra-smooth message list.
  - Full observability: thinking process, tool call chains, SubAgent execution status, active subprocesses, and complete LLM HTTP request/response logs — all traceable end to end.
- **Unique features**:
  - Minimal Skill mode (toggleable): when enabled, all Skills are triggered on demand via explicit commands, instead of dumping the whole catalog on the LLM — zero token cost in normal use. Perfect for users with many Skills: ultra token-saving, ultra low-noise!
  - Extreme Windows friendliness: auto-detects git bash and prefers bash, with deeply optimized character escaping. When bash is unavailable and it falls back to cmd, the command execution tool performs automatic line-level encoding detection, solving garbled output as much as possible. On Windows, the AI experience far surpasses comparable products — head and shoulders above the rest!
- **Pure & secure**: 100% portable, no backdoors, with path sandbox support.

**Engine & Architecture**

- **Overall**: Web UI, decoupled frontend and backend, standalone desktop shell.
- **Backend highlights**: engine and host are separated.
  - **Engine side**: a self-developed general-purpose Java ReAct framework. Abstract design, zero Spring dependencies, compatible with any ecosystem. Not tied to a specific host environment — it can run locally or be integrated into SaaS services. Built on a callback-driven loop, with built-in state management, SubAgent, tool invocation, permission management, event bus, context-window compaction, overflow-proof trimming, self-healing on exceptions, and other core capabilities. Provides abstractions such as prompt contributors and tool interfaces for hosts to implement.
  - **Host side**: the actual runtime environment. It implements the abstractions defined by the engine side and enriches concrete Coding Agent features on top of them.

---

## 🛠️ Tech Stack

| Module | Technology | Description |
| :--- | :--- | :--- |
| **Backend** (`st-cute-core`) | Java 25 / Spring Boot 4.1 | ReAct engine + local CodingAgent host |
| **Frontend** (`st-cute-web`) | Vue 3 + Vite 8 + TypeScript 6 | Naive UI component library, managed via pnpm workspace |
| **Desktop Shell** (`st-cute-desktop`) | Rust + Tauri 2 | Native window shell, double-click to run, auto-manages backend lifecycle |

---

## ✨ Feature Overview

- **Implemented**
    - ReAct Loop
    - SubAgent
    - Tool Invocation Matrix
        - File operations
        - Command execution
        - Online search
        - Meta information
    - Componentized Harness
        - SKILL
        - MCP
        - HOOK
        - RULE
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
    - Multi-language support for backend response messages and LLM built-in prompts
    - Multiple theme colors

---

## 🚀 Quick Start

This document covers downloading and running pre-compiled packages, security access configuration, and local source code development and building.

---

### 📦 Run Pre-compiled Packages Directly (Recommended)

You can download the archive for your system directly from the GitHub **[Releases](https://github.com/seteliu/st-cute/releases)** page and run it out of the box.

> [!NOTE]
> The core of ST-Cute is a **Java backend service** that relies on a **JRE** (Java Runtime Environment): the `desktop` / `bundle` packages bundle it, while the `base` package requires your own Java.

#### 🧭 Three Package Types

| Type | Includes | Best For |
|:--|:--|:--|
| **base** Minimal | Only `app.jar`, no bundled JRE or launcher scripts | Users who already have Java 25+ |
| **bundle** All-in-One | `app.jar` + bundled JRE + launcher scripts, start in terminal, access via browser | Server deployment / running in the background |
| **desktop** Desktop App | Desktop shell + `app.jar` + bundled JRE, double-click to use | The first choice for most users |

#### 📋 Download List

| Platform / Package | Type | Launch Method |
|:--|:--|:--|
| **`st-cute-base-x.x.x.zip`** | base | Run **`java -jar app.jar`** (bring your own Java 25+) |
| **`st-cute-bundle-win-x64-x.x.x.zip`** | bundle | Unzip and double-click **`st-cute.cmd`** |
| **`st-cute-bundle-linux-x64-x.x.x.tar.gz`** | bundle | Unzip and run **`./st-cute.sh`** in a terminal |
| **`st-cute-bundle-mac-arm64-x.x.x.tar.gz`** | bundle (Apple Silicon / M series) | Unzip and double-click **`st-cute.command`** |
| **`st-cute-bundle-mac-x64-x.x.x.tar.gz`** | bundle (Intel) | Unzip and double-click **`st-cute.command`** |
| **`st-cute-desktop-win-x64-x.x.x.zip`** | desktop | Unzip and double-click **`st-cute.exe`** |

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
> 3. For public exposure it is **strongly recommended to terminate HTTPS at a reverse proxy**: over plain HTTP both the access code and the session cookie travel across the network in cleartext and can be sniffed by any node along the path.

#### Configuring the Access Code for Headless Deployments (Docker / Servers)

The backend cannot obtain the plaintext of the access code (the page only uploads a digest when saving), so for headless scenarios such as containers, write the **plaintext** directly into the global config file. On startup the backend detects it automatically and upgrades it to a salted digest (after the upgrade the plaintext is no longer persisted):

```bash
# File location: ~/.st-cute/config.json (inside the container for Docker; mounting it as a data volume is recommended)
{
  "st-cute": {
    "password": "YourPass123"
  }
}
```

* The plaintext must satisfy the security policy: **8–32 characters**, containing both letters and digits, and using only common half-width characters;
* If it does not comply, the startup log prints an **ERROR** and keeps the original value without migrating it (making manual correction easy), and logins are rejected as well;
* You can also set it from the page while the service is running — the effect is identical.

#### Reverse Proxy Deployment Notes

If you use a reverse proxy such as Nginx / Caddy, you **must preserve the original Host header** and forward the protocol information, otherwise you will get site-wide 403s or broken login sessions:

```nginx
location / {
    proxy_pass http://127.0.0.1:9661;
    # Must be preserved: the backend uses it to verify request origin (Host and Origin must match)
    proxy_set_header Host $http_host;
    # Must be forwarded: the backend uses it to detect HTTPS, so session cookies get marked Secure
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # WebSocket upgrade support (required for real-time event push)
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

* The backend has built-in same-origin validation (applied to state-changing requests and WebSocket handshakes): the request's `Origin` must match `Host`.
  It adapts to any domain, IP, port, and protocol, with **no allowlist configuration needed**;
* If the proxy does not preserve the Host header (rewriting it to `127.0.0.1:9661`), it no longer matches the browser's Origin and POST/WS requests are rejected with 403;
* If a special deployment shape genuinely needs to allow extra origins, append entries to `st-cute.security.trusted-origins` in `application.yml`
  (both `example.com` and `example.com:8443` forms are supported); the desktop shell's fixed origin `tauri.localhost` is allowed by default;
* **Non-browser clients** (curl, Postman, CLI tools) do not send an Origin header, are unaffected by same-origin validation, and work normally.

---

### 💻 Source Code Development & Debugging (Developer Mode)

If you want to do secondary development or build from source:

#### Development Environment Requirements
* **Java JDK**: `Java 25` or higher
* **Build Tool**: `Maven 3.9+`
* **Node.js**: `Node.js 22+` & `pnpm 11+`

#### Start Backend Service (`st-cute-core`, host module `st-cute-service`)
```bash
cd st-cute-core/st-cute-service
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

Modular documentation is available under the `st-cute-core/st-cute-service/src/main/resources/docs/` directory. Click the links below for quick reference:

* 🚀 **[Quick Start](#-quick-start)**: Package download, environment requirements, and deployment.
* 📋 **[File Conventions](st-cute-core/st-cute-service/src/main/resources/docs/02_file_conventions_en.md)**: Files referenced and produced at runtime.
* ⚙️ **[RULE Configuration](st-cute-core/st-cute-service/src/main/resources/docs/05_RULE_en.md)**: `AGENTS.md` rule definitions and agent behavior constraints.
* 🧩 **[SKILL Extension Guide](st-cute-core/st-cute-service/src/main/resources/docs/06_SKILL_en.md)**: Custom skill declarations and the loading mechanism.
* 🔌 **[MCP Protocol Integration](st-cute-core/st-cute-service/src/main/resources/docs/07_MCP_en.md)**: Model Context Protocol (MCP) server configuration and tool mapping.
* ⚓ **[HOOK Mechanism](st-cute-core/st-cute-service/src/main/resources/docs/08_HOOK_en.md)**: Lifecycle interception before and after tool calls.

---

## 📄 License

This project is open-sourced under the [MIT License](LICENSE); feel free to use, modify, and distribute.
