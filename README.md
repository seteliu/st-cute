<p align="center">
  <img src="st-cute-web/public/favicon.svg" width="96" height="96" alt="ST-Cute Logo" />
</p>

<h1 align="center">ST-Cute</h1>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README_EN.md">English</a>
</p>

**ST-Cute** 是一个前后端分离、天然支持多终端使用的 AI Coding Agent & Harness。
基于事件的ReAct Loop，支持RULE、SKILL、MCP、HOOK，兼容主流 .agents 目录配置。
对于大模型的http请求和agent的工具调用，可观测，好驾驭。
在简陋与臃肿之间，它是一个刚刚好的轻量级选手。

[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-green.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3.x-brightgreen.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 📸 界面预览

| 📱 移动端适配 (Vibe Coding on Mobile) | 💻 PC 桌面端全景交互 |
| :---: | :---: |
| ![移动端预览](.github/assets/mobile-preview.jpg) | ![桌面端预览](.github/assets/desktop-preview.jpg) |

---

## 🎯 适用的用户
* 💡 **想了解 Coding Agent 原理**：代码结构简洁，麻雀虽小五脏俱全，有设计但不过度设计，是绝佳的 Java 编写 Agent 范例。
* 🔍 **追求高掌控力与完整的可观测性**：清晰监测工具调用链、SubAgent 执行状态、活跃子进程、大模型 HTTP 完整请求响应日志。
* 📱 **想部署跨终端 Agent**：B/S 架构，WebUI 响应式适配，PC 端与移动端同源，“一处部署，到处可用”。
* 🛡️ **安全纯粹**：纯绿色、无后门、支持路径沙箱。

---

## 🛠️ 技术栈

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **后端** (`st-cute-service`) | Java 25 / Spring Boot 4.1 | 事件驱动与轻量架构 |
| **持久层** | SQLite 3 + MyBatis-Flex | 默认开启 WAL 模式，无需安装繁重数据库 |
| **网络与通信** | OkHttp + WebSocket | REST API + 实时双向通信，完整 HTTP 探针日志 |
| **前端** (`st-cute-web`) | Vue 3 + Vite 8 + TypeScript 6 | Naive UI 基础库，pnpm workspace 管理 |

---

## ✨ 功能全景

- **已实现**
    - ReAct Loop
    - 工具调用
        - 文件搜索与读写
        - 命令执行
        - Git WorkTree
    - 多模态
        - 图片 / PDF 以原生多模态内容注入大模型（OpenAI、Anthropic 双协议）
        - Word / Excel / PPT 文档自动提取文本内容
        - 文本与代码文件内联解析
        - Agent 可通过工具回读历史会话附件
    - SKILL
    - MCP
    - HOOK
    - RULE（AGENTS.md）
    - SubAgent
    - 权限管控
        - 只读
        - 智能审批
        - 路径沙箱
    - 自定义供应商
        - 支持 OpenAI Chat、Anthropic Claude 两种基础协议
        - 可配置上下文窗口大小
        - 可配置单次最大 Token 数（max_tokens）
        - 可配置思考级别
        - 可配置温度
    - 体验优化
        - 自定义换行键
        - 消息聚合展示开关
        - 路径沙箱开关
        - 大模型完整http日志开关
        - 安全访问码
    - 多终端
        - 支持移动端
    - 多语言
        - 前端支持中英双语
- **暂未发布，在计划中**
    - 在线搜索
    - OpenAI Response 协议
    - 后端响应msg及大模型内置提示词增加多语言支持
    - 多主题配色

---

## 🚀 快速开始

本文档提供 ST-Cute 的安装包下载运行、安全访问设置，以及本地源码开发与构建指南。

---

### 📦 预编译安装包直接运行（推荐）

您可以直接在 GitHub 的 **[Releases](releases)** 页面下载对应系统的压缩包开箱即用：

| 平台 / 包名 | 包含内容 | 启动方式 |
| :--- | :--- | :--- |
| **`st-cute-win-x64-x.x.x.zip`** | 内置裁剪 JRE + `st-cute.cmd` | 解压后双击 **`st-cute.cmd`** |
| **`st-cute-linux-x64-x.x.x.tar.gz`** | 内置裁剪 JRE + `st-cute.sh` | 解压后在终端运行 **`./st-cute.sh`** |
| **`st-cute-mac-arm64-x.x.x.tar.gz`** | 内置裁剪 JRE (Apple Silicon，M系列芯片) | 解压后双击 **`st-cute.command`**（或终端运行 `./st-cute.sh`） |
| **`st-cute-mac-x64-x.x.x.tar.gz`** | 内置裁剪 JRE (Intel 芯片 Mac) | 解压后双击 **`st-cute.command`**（或终端运行 `./st-cute.sh`） |
| **`st-cute-base-x.x.x.zip`** | 仅纯 JAR 包（无需内置 JRE） | 自备 Java 25+，运行 **`java -jar app.jar`** |

> [!TIP]
> **Mac 首次双击提示“Apple无法验证 / 已阻止”处理办法**（仅需设置一次）：
> 1. 首次双击提示被阻止后，点击【完成】关闭弹窗；
> 2. 打开 Mac **【系统设置】 ➔ 【隐私与安全性】**；
> 3. 页面向下滑动到 **“安全性”** 区域，点击 **【仍要打开】 (Open Anyway)** 并输入锁屏密码；
> 4. 完成后，以后直接双击 **`st-cute.command`** 即可流畅运行！

#### 🌐 默认访问地址
服务启动成功后，打开浏览器访问：
👉 **`http://localhost:9661`**

---

### ⚠️ 安全访问与公网/移动端映射（必读）

> [!WARNING]
> **重要安全提示**：
> 如果您准备进行**公网端口映射**，或从**移动端设备（如手机/平板等外部网络）**连接访问部署的 ST-Cute：
> 1. 请务必在服务启动后，先在系统 **【设置】** 页面中配置 **安全访问码 (Access Security Code)**；
> 2. 设置并生效访问码后，再将 `9661` 端口映射到局域网外或公网，切勿将无保护的服务直接裸露在公网环境！

---

### 💻 源码开发与调试（开发者模式）

如果您希望基于源码进行二次开发或构建：

#### 开发环境要求
* **Java JDK**: `Java 25` 或更高版本
* **构建工具**: `Maven 3.9+`
* **Node.js**: `Node.js 22+` & `pnpm 11+`

#### 启动后端服务 (`st-cute-service`)
```bash
cd st-cute-service
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

---

## 📖 详细文档导览

项目在 `st-cute-service/src/main/resources/docs/` 目录下提供了完整的模块化文档，点击下方链接快速查阅：

* 🚀 **[快速开始](#-快速开始)**：包含安装包下载、环境要求与部署运行。
* 📋 **[文件规约](st-cute-service/src/main/resources/docs/02_file_conventions.md)**：程序在运行中对于引用和产生的文件说明。
* ⚙️ **[RULE 规则配置](st-cute-service/src/main/resources/docs/05_RULE.md)**：`AGENTS.md` 规则定义与智能体行为约束。
* 🧩 **[SKILL 扩展指南](st-cute-service/src/main/resources/docs/06_SKILL.md)**：自定义技能声明与加载机制。
* 🔌 **[MCP 协议接入](st-cute-service/src/main/resources/docs/07_MCP.md)**：Model Context Protocol (MCP) Server 配置与工具映射。
* ⚓ **[HOOK 钩子机制](st-cute-service/src/main/resources/docs/08_HOOK.md)**：工具调用前后的生命周期拦截。

---

## 为什么写这个项目？
最初是为了在 AI 浪潮中亲自感受“Coding Agent 是怎么从 0 到 1 做出来的”，所以亲手写一遍。
与此同时，其他agent或多或少有些不满足的点：
- 比如我想清晰地监测工具调用和完整的http日志。
- 比如，我想在家里部署后，出去后手机进行连接，在地铁上也想Vibe Coding一下。

然后就发现，我可以更进一步，它不只是玩具，已经是实用品。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 许可证开源，欢迎自由使用、修改与分发。
