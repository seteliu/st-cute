<p align="center">
  <img src="st-cute-web/public/favicon.svg" width="96" height="96" alt="ST-Cute Logo" />
</p>

<h1 align="center">ST-Cute</h1>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README_EN.md">English</a>
</p>

**ST-Cute** 是一个简约好用、功能齐全、同时提供桌面端与网页端的 AI Coding Agent & Harness。

[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3.x-brightgreen.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/seteliu/st-cute)](https://github.com/seteliu/st-cute/releases)
[![CI](https://github.com/seteliu/st-cute/actions/workflows/ci.yml/badge.svg)](https://github.com/seteliu/st-cute/actions/workflows/ci.yml)

---

## 🏛️ 项目特色

**使用与体验**

- **设计哲学**：不能不设计，也不能过度设计。在简陋与臃肿之间恰到好处。如果 AI 是匹野马，更多的时候，要的不是笨重的航母，而是一套简单易用的马具。
- **多形态包**：提供多种形态的 release 包，适配多种环境，桌面与无UI服务器都可部署，解压即跑。
- **多端同步**：一处部署，桌面端 / 网页端 / 移动端同步访问完整功能，响应式适配，手机也能随地 Vibe Coding。
- **办公支持**：PDF / Word / Excel 内置工具直接读取，无需外挂转换。
- **美好体验**：
  - 齐全又克制的内置功能，让你没有负担。将你的创意舞台，交给组件化 Harness。
  - 智能折叠降噪，超流畅的消息列表。
  - 可观测：思考过程、工具调用链、SubAgent 执行状态、活跃子进程、大模型 HTTP 完整请求响应日志全程可溯。
- **独创特色**：
  - 极简 Skill 模式：可开关，开启后所有 Skill 通过显示命令按需触发，不会无脑把目录给大模型，平常情况 0 Token 消耗。适用于很多 Skill 的用户，超省 Token，超小干扰！
  - 极致 Windows 友好：自动探测 git bash，bash 优先，深度优化字符转义。无 bash 回退 cmd 方案时，命令执行工具自动行级编码探测，最大可能解决乱码问题。在 Windows 环境下，AI 体感远超同类产品！
- **安全纯粹**：纯绿色、无后门、支持路径沙箱。

**引擎与架构**

- **整体介绍**：WebUI、前后端分离、独立桌面壳。
- **后端特色**：引擎与宿主分离。
    - **引擎侧**：自研的通用 Java ReAct 框架。抽象设计，零 Spring 依赖，生态通吃。不限宿主环境，可运用于本地，可集成于 SaaS 服务。基于回调驱动的循环，内建状态管理、SubAgent、工具调用、权限管理、事件总线、窗口压缩、防爆裁剪、异常情况自愈等基础能力。抽象提示词贡献器，工具接口等供宿主侧实现。
    - **宿主侧**：作为实际运行环境，实现引擎侧的抽象定义，丰富落地 Coding Agent 相关功能。

---

## 🛠️ 技术栈

| 模块 | 技术选型                      | 说明                                         |
| :--- |:------------------------------|:---------------------------------------------|
| **后端** (`st-cute-core`) | Java 25 / Spring Boot 4.1     | ReAct 引擎 + 本地 CodingAgent 宿主           |
| **前端** (`st-cute-web`) | Vue 3 + Vite 8 + TypeScript 6 | Naive UI 基础库，pnpm workspace 管理         |
| **桌面壳** (`st-cute-desktop`) | Rust + Tauri2                 | 原生窗体外壳，双击即用，自动托管后端生命周期 |

---

## ✨ 功能全景

- **已实现**
    - ReAct Loop
    - SubAgent
    - 工具调用矩阵
        - 文件操作
        - 命令执行
        - 在线搜索
        - 元信息提供
    - 组件化 Harness
        - SKILL
        - MCP
        - HOOK
        - RULE
    - 权限管控
        - 只读
        - 智能审批
        - 路径沙箱
    - 自定义供应商
        - 支持 OpenAI Chat、OpenAI Response、Anthropic Claude 三种协议
        - 可配置完整的 url（比如地址非 /chat/completions 的情况）
        - 可配置上下文窗口大小
        - 可配置单次最大 Token 数（max_tokens）
        - 可配置思考级别
        - 可配置温度
    - 多模态
        - 图片
        - 内置工具原生支持 PDF / Word / Excel / PPT
    - 体验优化
        - 自定义换行键
        - 消息聚合展示开关
        - 路径沙箱开关
        - 大模型完整http日志开关
        - 安全访问码
    - 多终端
        - 桌面客户端（Rust 桌面壳 + 内置 JRE，开箱即用）
        - 网页端响应式适配，支持移动端
    - 多语言
        - 前端支持中英双语
- **暂未发布，在计划中**
    - 后端响应msg及大模型内置提示词增加多语言支持
    - 多主题配色

---

## 🚀 快速开始

本文档提供 ST-Cute 的安装包下载运行、安全访问设置，以及本地源码开发与构建指南。

---

### 📦 预编译安装包直接运行（推荐）

您可以直接在 GitHub 的 **[Releases](https://github.com/seteliu/st-cute/releases)** 页面下载对应系统的压缩包开箱即用。

> [!NOTE]
> ST-Cute 核心是一个 **Java 后端服务**，运行依赖 **JRE**（Java 运行环境）：`desktop` / `bundle` 包已内置，`base` 包需自备。

#### 🧭 三种包类型

| 类型 | 包含内容 | 适合谁 |
|:--|:--|:--|
| **base** 精简版 | 仅 `app.jar`，无内置 JRE 与启动脚本 | 已自备 Java 25+ 环境的用户 |
| **bundle** 整合版 | `app.jar` + 内置 JRE + 启动脚本，终端启动、浏览器访问 | 部署到服务器 / 希望挂后台运行的用户 |
| **desktop** 桌面版 | 桌面程序壳 + `app.jar` + 内置 JRE，双击即用 | 大多数用户的首选 |

#### 📋 下载清单

| 平台 / 包名 | 类型 | 启动方式 |
|:--|:--|:--|
| **`st-cute-base-x.x.x.zip`** | base | 运行 **`java -jar app.jar`**（需自备 Java 25+） |
| **`st-cute-bundle-win-x64-x.x.x.zip`** | bundle | 解压后双击 **`st-cute.cmd`** |
| **`st-cute-bundle-linux-x64-x.x.x.tar.gz`** | bundle | 解压后在终端运行 **`./st-cute.sh`** |
| **`st-cute-bundle-mac-arm64-x.x.x.tar.gz`** | bundle（M 系列芯片） | 解压后双击 **`st-cute.command`** |
| **`st-cute-bundle-mac-x64-x.x.x.tar.gz`** | bundle（Intel 芯片） | 解压后双击 **`st-cute.command`** |
| **`st-cute-desktop-win-x64-x.x.x.zip`** | desktop | 解压后双击 **`st-cute.exe`** |

> [!TIP]
> **Mac 首次双击提示“Apple无法验证 / 已阻止”处理办法**（仅需设置一次）：
> 1. 首次双击提示被阻止后，点击【完成】关闭弹窗；
> 2. 打开 Mac **【系统设置】 ➔ 【隐私与安全性】**；
> 3. 页面向下滑动到 **“安全性”** 区域，点击 **【仍要打开】 (Open Anyway)** 并输入锁屏密码；
> 4. 完成后，以后直接双击 **`st-cute.command`** 即可流畅运行！

#### 🌐 网页访问地址
👉 **`http://localhost:9661`**

---

### ⚠️ 安全访问与公网/移动端映射（必读）

> [!WARNING]
> **重要安全提示**：
> 如果您准备进行**公网端口映射**，或从**移动端设备（如手机/平板等外部网络）**连接访问部署的 ST-Cute：
> 1. 请务必在服务启动后，先在系统 **【设置】** 页面中配置 **安全访问码 (Access Security Code)**；
> 2. 设置并生效访问码后，再将 `9661` 端口映射到局域网外或公网，切勿将无保护的服务直接裸露在公网环境！
> 3. 公网暴露**强烈建议经反向代理终结 HTTPS**：裸 HTTP 下访问码与会话 Cookie 均以明文过网，可被链路上任何节点嗅探。

#### 无界面部署（Docker / 服务器）如何配置访问码

后端无法获取访问码原文（页面保存时仅上传摘要），因此容器等无界面场景请直接在全局配置文件中写入**明文**，
后端启动时会自动识别并升级为带盐摘要存储（升级后原文不再落盘）：

```bash
# 文件位置：~/.st-cute/config.json（Docker 下为容器内该路径，建议挂载为数据卷）
{
  "st-cute": {
    "password": "YourPass123"
  }
}
```

* 明文须满足安全策略：**8~32 位**、同时包含英文字母与数字、仅可使用常见半角字符；
* 不合规时启动日志会打印 **ERROR** 并保留原值不迁移（便于手工修正），登录也会被拒绝；
* 也可在服务运行后经页面设置，效果一致。

#### 反向代理部署要点

若使用 Nginx / Caddy 等反向代理，**必须保留原始 Host 头**并传递协议信息，否则会出现全站 403 或登录态异常：

```nginx
location / {
    proxy_pass http://127.0.0.1:9661;
    # 必须保留：后端以此校验请求来源同源性（Host 与 Origin 必须一致）
    proxy_set_header Host $http_host;
    # 必须传递：后端据此识别 HTTPS，会话 Cookie 才会被自动标记 Secure
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # WebSocket 升级支持（实时事件推送必需）
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

* 后端已内置同源校验（针对状态变更类请求与 WebSocket 握手）：请求的 `Origin` 须与 `Host` 一致，
  可自适应任意域名、IP、端口与协议，**无需配置白名单**；
* 若代理未保留 Host 头（被改写成 `127.0.0.1:9661`），则与浏览器 Origin 不同源，POST/WS 将被 403 拒绝；
* 特殊部署形态确需放行额外来源时，可在 `application.yml` 的 `st-cute.security.trusted-origins` 中追加条目
  （支持 `example.com` 或 `example.com:8443` 两种写法）；桌面壳固定来源 `tauri.localhost` 已内置放行；
* **非浏览器客户端**（curl、Postman、命令行工具）不携带 Origin 头，不受同源校验影响，可正常调用。

---

### 💻 源码开发与调试（开发者模式）

如果您希望基于源码进行二次开发或构建：

#### 开发环境要求
* **Java JDK**: `Java 25` 或更高版本
* **构建工具**: `Maven 3.9+`
* **Node.js**: `Node.js 22+` & `pnpm 11+`

#### 启动后端服务 (`st-cute-core`，宿主模块 `st-cute-service`)
```bash
cd st-cute-core/st-cute-service
mvn clean spring-boot:run
```
* 开发调试模式下后端服务运行在 `http://localhost:9661`。
* **数据库**：首次运行会在用户主目录 `.st-cute/` 文件夹下自动生成 `st-cute.db`（SQLite WAL 模式）。

#### 启动前端服务 (`st-cute-web`)
```bash
cd st-cute-web
pnpm install
pnpm dev
```
* 开发调试模式下前端服务运行在 `http://localhost:9662`。

#### 桌面客户端壳 (`st-cute-desktop`)（非必须）

日常开发只需启动前后端服务，浏览器访问即可；仅在需要开发或调试 Rust 桌面壳本身时才需要关注此模块：
* **技术栈**：Rust + Tauri 2，负责原生窗体外壳与后端生命周期托管
* **环境要求**：Rust 工具链（`cargo`，Windows 下为 MSVC target）
* **运行依赖**：壳会加载后端产物（`app.jar` + JRE），本地调试时需先将其置于 `st-cute-desktop/src-tauri/resources` 目录

---

## 📖 详细文档导览

项目在 `st-cute-core/st-cute-service/src/main/resources/docs/` 目录下提供了完整的模块化文档，点击下方链接快速查阅：

* 🚀 **[快速开始](#-快速开始)**：包含安装包下载、环境要求与部署运行。
* 📋 **[文件规约](st-cute-core/st-cute-service/src/main/resources/docs/02_file_conventions.md)**：程序在运行中对于引用和产生的文件说明。
* ⚙️ **[RULE 规则配置](st-cute-core/st-cute-service/src/main/resources/docs/05_RULE.md)**：`AGENTS.md` 规则定义与智能体行为约束。
* 🧩 **[SKILL 扩展指南](st-cute-core/st-cute-service/src/main/resources/docs/06_SKILL.md)**：自定义技能声明与加载机制。
* 🔌 **[MCP 协议接入](st-cute-core/st-cute-service/src/main/resources/docs/07_MCP.md)**：Model Context Protocol (MCP) Server 配置与工具映射。
* ⚓ **[HOOK 钩子机制](st-cute-core/st-cute-service/src/main/resources/docs/08_HOOK.md)**：工具调用前后的生命周期拦截。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 许可证开源，欢迎自由使用、修改与分发。
