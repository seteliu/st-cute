#!/usr/bin/env node
/**
 * 官方启动脚本生成器（st-cute.cmd / st-cute.sh / st-cute.command）
 *
 * 单一真源：GitHub Actions (release.yml) 与本地构建 (cmd/build-dist-base.cmd) 统一调用本生成器，
 * 杜绝两处源头手工维护同一份脚本导致的漂移。脚本内置升级接力机制：
 *   1. 启动前断点续装：发现 updates/app.jar.new 则完成交换（处理升级中断遗留）
 *   2. 退出码 88 分派：应用主动升级重启 → 换包后重新拉起
 *   3. 一次性回滚：新版本异常退出且存在 app.jar.bak 时自动回退上一版本
 * 同时声明底座身份环境变量（ST_CUTE_LAUNCHER_TYPE / ST_CUTE_LAUNCHER_VERSION），
 * 供 Java 侧 LauncherType 判定运行方式与底座版本。
 *
 * 用法：
 *   node gen-launcher-scripts.mjs --out <输出目录> [--version <版本号>]
 *   未显式传版本号时自动读取 st-cute-core/pom.xml 的项目版本。
 */

import fs from 'node:fs'
import path from 'node:path'

// ── 参数解析 ──
const args = process.argv.slice(2)
const outIndex = args.indexOf('--out')
const verIndex = args.indexOf('--version')
const outDir = outIndex !== -1 ? args[outIndex + 1] : null
if (!outDir) {
  console.error('[gen-launcher] 缺少 --out 参数（输出目录）')
  process.exit(1)
}

// ── 版本号解析：显式入参 > 根 pom 项目版本 ──
function resolveVersion() {
  if (verIndex !== -1 && args[verIndex + 1]) {
    return args[verIndex + 1]
  }
  const pomPath = path.resolve(import.meta.dirname, '../st-cute-core/pom.xml')
  const pom = fs.readFileSync(pomPath, 'utf8')
  // 跳过 <parent> 块，取项目自身 <version>（与 release.yml 的解析口径一致）
  const tail = pom.slice(pom.indexOf('</parent>'))
  const match = tail.match(/<version>([^<]+)<\/version>/)
  if (!match) {
    console.error('[gen-launcher] 无法从 st-cute-core/pom.xml 解析版本号，请显式传 --version')
    process.exit(1)
  }
  return match[1].trim()
}

const version = resolveVersion()

// ── Windows 启动脚本（CRLF，纯 ASCII）──
// 刻意不含任何非 ASCII 字符：cmd 解析器按系统 OEM 代码页（中文 Windows 为 GBK）读取批处理，
// UTF-8 中文注释会被 GBK 多字节配对撕裂，破坏 if (...) 块结构与引号配对（实测 'ak\"' 残片报错）。
// 提示信息一律英文，与既有 build-dist-base.cmd 生成惯例一致；中文提示仅保留在 sh 脚本（bash 原生 UTF-8）。
const cmdScript = `@echo off
rem Switch console code page to UTF-8: Java 25 emits UTF-8 logs by default (JEP 400),
rem while cmd defaults to the legacy GBK code page and would show mojibake without this.
rem NOTE: this file must stay pure ASCII (except this comment block being English) --
rem cmd parses it under GBK before chcp takes effect, non-ASCII bytes can corrupt block structure.
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem == Upgrade relay: resume interrupted swap before startup ==
:PREPARE
if exist "updates\\app.jar.new" (
    if exist "app.jar.bak" del /f /q "app.jar.bak"
    if exist "app.jar" ren "app.jar" "app.jar.bak"
    move /y "updates\\app.jar.new" "app.jar" >nul
)

set "JAVA_CMD="
if exist "%~dp0jre\\bin\\java.exe" (
    set "JAVA_CMD=%~dp0jre\\bin\\java.exe"
    goto :RUN
)
if exist "%~dp0jdk\\bin\\java.exe" (
    set "JAVA_CMD=%~dp0jdk\\bin\\java.exe"
    goto :RUN
)
where java >nul 2>nul
if %errorlevel% equ 0 (
    set "JAVA_CMD=java"
    goto :RUN
)

echo [ERROR] Java runtime not found!
echo Please place jre or jdk under this directory, or configure system Java environment.
pause
exit /b 1

:RUN
rem == Launcher identity: Java side detects SCRIPT run mode and launcher version ==
set "ST_CUTE_LAUNCHER_TYPE=SCRIPT"
set "ST_CUTE_LAUNCHER_VERSION=${version}"
set "JAVA_OPTS=%ST_CUTE_JAVA_OPTS%"
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms256m -Xmx1024m"
"%JAVA_CMD%" %JAVA_OPTS% -jar "%~dp0app.jar" %*
set "EXIT_CODE=%errorlevel%"

rem == Upgrade relay: exit code dispatch ==
rem 88 = app requested upgrade restart: swap package then restart via :PREPARE
if "%EXIT_CODE%"=="88" (
    echo [st-cute] Upgrade restart requested, switching to new version
    goto :PREPARE
)
rem Abnormal exit with backup present: one-shot rollback (avoid bricking user; no infinite loop)
if not "%EXIT_CODE%"=="0" (
    if exist "app.jar.bak" (
        echo [st-cute] New version failed, exit code %EXIT_CODE%, rolling back
        if exist "app.jar" del /f /q "app.jar"
        ren "app.jar.bak" "app.jar"
        goto :PREPARE
    )
)
exit /b %EXIT_CODE%
`

// ── Linux/macOS 启动脚本（LF）──
const shScript = `#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")"; pwd)"
cd "$SCRIPT_DIR"

# ── 升级接力 · 启动前断点续装 ──
prepare_update() {
    if [ -f "updates/app.jar.new" ]; then
        [ -f app.jar.bak ] && rm -f app.jar.bak
        [ -f app.jar ] && mv -f app.jar app.jar.bak
        mv -f updates/app.jar.new app.jar
    fi
}

# ── 底座身份声明 ──
export ST_CUTE_LAUNCHER_TYPE=SCRIPT
export ST_CUTE_LAUNCHER_VERSION="${version}"

run_once() {
    JAVA_CMD=""
    if [ -x "$SCRIPT_DIR/jre/bin/java" ]; then
        JAVA_CMD="$SCRIPT_DIR/jre/bin/java"
    elif [ -x "$SCRIPT_DIR/jdk/bin/java" ]; then
        JAVA_CMD="$SCRIPT_DIR/jdk/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_CMD="java"
    fi

    if [ -z "$JAVA_CMD" ]; then
        echo "[ERROR] 未找到 Java 环境！"
        echo "请在当前目录下放置 jre 或 jdk 目录，或者配置系统 Java 环境变量。"
        exit 1
    fi

    JAVA_OPTS="\${ST_CUTE_JAVA_OPTS:--Xms256m -Xmx1024m}"
    "$JAVA_CMD" $JAVA_OPTS -jar "$SCRIPT_DIR/app.jar" "$@"
}

prepare_update
while true; do
    run_once "$@"
    EXIT_CODE=$?
    if [ "$EXIT_CODE" -eq 88 ]; then
        echo "[st-cute] 检测到升级重启指令，正在替换新版本..."
        prepare_update
        continue
    fi
    if [ "$EXIT_CODE" -ne 0 ] && [ -f app.jar.bak ]; then
        echo "[st-cute] 新版本启动失败(退出码 $EXIT_CODE)，自动回滚上一版本..."
        rm -f app.jar
        mv -f app.jar.bak app.jar
        prepare_update
        continue
    fi
    exit $EXIT_CODE
done
`

// ── 输出落盘 ──
// 行尾显式归一化：模板字符串继承本源文件的行尾风格（Windows 下为 CRLF），
// sh 产物必须 LF、cmd 产物必须 CRLF，故先统一转 LF 再按目标格式重排，杜绝宿主平台差异渗入产物
const normalizeLf = (s) => s.replaceAll('\r\n', '\n')
fs.mkdirSync(outDir, { recursive: true })
fs.writeFileSync(path.join(outDir, 'st-cute.cmd'), normalizeLf(cmdScript).replaceAll('\n', '\r\n'), 'utf8')
fs.writeFileSync(path.join(outDir, 'st-cute.sh'), normalizeLf(shScript), 'utf8')
fs.copyFileSync(path.join(outDir, 'st-cute.sh'), path.join(outDir, 'st-cute.command'))
fs.chmodSync(path.join(outDir, 'st-cute.sh'), 0o755)
fs.chmodSync(path.join(outDir, 'st-cute.command'), 0o755)

console.log(`[gen-launcher] 已生成启动脚本 (版本 ${version})：`)
console.log(`  ${path.join(outDir, 'st-cute.cmd')}`)
console.log(`  ${path.join(outDir, 'st-cute.sh')}`)
console.log(`  ${path.join(outDir, 'st-cute.command')}`)
