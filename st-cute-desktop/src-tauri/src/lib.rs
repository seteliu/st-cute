mod logging;
mod probe;
mod process;
mod shutdown;
mod update;

use process::ProcessManager;
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, sleep};
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager, WindowEvent};
use tauri_plugin_window_state::{StateFlags, WindowExt};

const BASE_URL: &str = "http://127.0.0.1:9661";
static STARTUP_RUNNING: AtomicBool = AtomicBool::new(false);
/// 退出观测线程防重标志：服务就绪后的常驻 watcher 仅允许一个实例
static EXIT_WATCHING: AtomicBool = AtomicBool::new(false);

#[tauri::command]
fn open_log_dir() -> Result<(), String> {
    logging::open_log_dir()
}

#[tauri::command]
fn app_ready(app: tauri::AppHandle) {
    trigger_startup_flow(app, false);
}

#[tauri::command]
fn retry_start(app: tauri::AppHandle) {
    trigger_startup_flow(app, true);
}

fn trigger_startup_flow(app: tauri::AppHandle, force_retry: bool) {
    if force_retry {
        STARTUP_RUNNING.store(false, Ordering::SeqCst);
    }

    if STARTUP_RUNNING.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
        return;
    }

    let app_handle = app.clone();
    thread::spawn(move || {
        let emit_status = |phase: &str, detail: Option<&str>| {
            let _ = app_handle.emit(
                "backend-status",
                json!({
                    "phase": phase,
                    "detail": detail.unwrap_or("")
                }),
            );
        };
        emit_status("probing", None);

        // 1. 初次探测：检查是否已有外部运行实例（不带凭证的开放探测）
        let probe_result = probe::probe_ping(BASE_URL, None);
        match probe_result {
            probe::ProbeResult::Ready { ref version, .. } => {
                // 复用模式：外部已有 st-cute 正在运行，直接进入
                emit_status("adopted", Some(version));
                navigate_to_main(&app_handle);
                STARTUP_RUNNING.store(false, Ordering::SeqCst);
                return;
            }
            probe::ProbeResult::PortConflict { ref detail } => {
                // 端口冲突：被非 st-cute 服务占用
                emit_status("error", Some(detail));
                STARTUP_RUNNING.store(false, Ordering::SeqCst);
                return;
            }
            probe::ProbeResult::NotRunning => {
                // 端口未被占用，继续走托管拉起流程
            }
        }

        // 检查开发模式标志：开发模式下不自动 spawn，由开发者自行启动后端
        if std::env::var("ST_CUTE_DESKTOP_DEV").as_deref() == Ok("1") {
            emit_status(
                "error",
                Some("开发模式 (ST_CUTE_DESKTOP_DEV=1)：未探测到本地 9661 服务，请在 IDE 启动后端后点击重试。"),
            );
            STARTUP_RUNNING.store(false, Ordering::SeqCst);
            return;
        }

        // 2. 托管模式：启动内置 Java 进程
        emit_status("starting", None);

        let process_state = app_handle.state::<Arc<Mutex<ProcessManager>>>();
        {
            let mut mgr = process_state.lock().unwrap();
            if !mgr.spawned {
                if let Err(err) = mgr.spawn_service() {
                    emit_status("error", Some(&err));
                    STARTUP_RUNNING.store(false, Ordering::SeqCst);
                    return;
                }
            }

            // 轮询读取后端落盘的桌面端凭证（仅凭证为空时尝试，后端原子写入存在短暂窗口期）
            if mgr.token.is_empty() {
                match process::read_desktop_token() {
                    Some(token) => mgr.token = token,
                    None => {
                        emit_status(
                            "error",
                            Some("获取桌面端凭证超时 (10s)，请查看后端日志排查凭证落盘异常。"),
                        );
                        STARTUP_RUNNING.store(false, Ordering::SeqCst);
                        return;
                    }
                }
            }
        }

        // 3. 高频自适应轮询健康状态（最多等待 60 秒）
        let start_time = Instant::now();
        let timeout = Duration::from_secs(60);

        while start_time.elapsed() < timeout {
            // 检查子进程是否已中途崩溃
            {
                let mut mgr = process_state.lock().unwrap();
                if let Some(status) = mgr.check_child_exited() {
                    handle_child_exit(&app_handle, &process_state, status);
                    return;
                }
            }

            // 快速探测服务是否已响应（托管模式下携带桌面端凭证）
            let token = {
                let mgr = process_state.lock().unwrap();
                mgr.token.clone()
            };
            let token_opt = if token.is_empty() { None } else { Some(token.as_str()) };
            if let probe::ProbeResult::Ready { .. } = probe::probe_ping(BASE_URL, token_opt) {
                emit_status("ready", None);
                navigate_to_main(&app_handle);
                // 升级闭环收尾：健康就绪即升级成功，清理备份与残留待装包；
                // 未处于升级流程时同样幂等清理（处理历史残留）
                update::cleanup_after_success();
                // 挂常驻退出观测：服务正常运行后健康轮询线程即将返回，此后子进程的
                // 退出（升级 88 / 崩溃 / 用户重启）由该 watcher 接管分派
                spawn_exit_watcher(&app_handle);
                STARTUP_RUNNING.store(false, Ordering::SeqCst);
                return;
            }

            // 前 3 秒高频探测（80ms），3 秒后平滑至 200ms
            let poll_interval = if start_time.elapsed() < Duration::from_secs(3) {
                Duration::from_millis(80)
            } else {
                Duration::from_millis(200)
            };
            sleep(poll_interval);
        }

        emit_status(
            "error",
            Some("后端服务启动超时 (60s)，请点击「打开日志目录」排查启动异常。"),
        );
        STARTUP_RUNNING.store(false, Ordering::SeqCst);
    });
}

/// 子进程退出分派：升级接力（88 + 待装包在位）/ 升级后回滚（非 88 + 备份在位）/ 普通异常上报。
/// 健康轮询期间的观测点与常驻 exit watcher 共用本函数，保证两条路径分派行为一致
fn handle_child_exit(
    app_handle: &tauri::AppHandle,
    process_state: &tauri::State<'_, Arc<Mutex<ProcessManager>>>,
    status: std::process::ExitStatus,
) {
    let emit_status = |phase: &str, detail: Option<&str>| {
        let _ = app_handle.emit(
            "backend-status",
            json!({
                "phase": phase,
                "detail": detail.unwrap_or("")
            }),
        );
    };

    // ── 升级接力：退出码 88 = 应用主动升级重启 ──
    // 仅当存在待就位新包（updates/app.jar.new）时按升级处理；
    // 否则视为普通异常退出（防御：退出码可被误触发，文件约定优先）
    if status.code() == Some(88) {
        let relay = update::JarRelay::new();
        if relay.has_pending() {
            emit_status("upgrading", None);
            match relay.swap() {
                Ok(()) => {
                    emit_status("upgrade-applied", None);
                    // 重置管理器状态后回到启动流程重新拉起新版（清 token 防旧凭证串扰）
                    {
                        let mut mgr = process_state.lock().unwrap();
                        mgr.spawned = false;
                        mgr.token.clear();
                    }
                    STARTUP_RUNNING.store(false, Ordering::SeqCst);
                    let app_handle2 = app_handle.clone();
                    thread::spawn(move || {
                        trigger_startup_flow(app_handle2, true);
                    });
                    return;
                }
                Err(err) => {
                    emit_status("error", Some(&format!("升级换包失败: {}", err)));
                    STARTUP_RUNNING.store(false, Ordering::SeqCst);
                    return;
                }
            }
        }
    }

    // ── 升级后新版启动失败的回滚观测 ──
    // 换包后拉起的新版若非 88 退出（崩溃/起不来），且存在备份：一次性回滚重启旧版
    if status.code() != Some(88) {
        let relay = update::JarRelay::new();
        if relay.has_backup() {
            eprintln!("[update] 换包后新版异常退出(退出码 {:?})，执行一次性回滚", status.code());
            if let Err(err) = relay.rollback() {
                emit_status("error", Some(&format!("升级回滚失败: {}", err)));
                STARTUP_RUNNING.store(false, Ordering::SeqCst);
                return;
            }
            let _ = app_handle.emit(
                "backend-status",
                json!({
                    "phase": "upgrade-rolled-back",
                    "detail": "新版本启动失败，已自动回滚上一版本"
                }),
            );
            {
                let mut mgr = process_state.lock().unwrap();
                mgr.spawned = false;
                mgr.token.clear();
            }
            STARTUP_RUNNING.store(false, Ordering::SeqCst);
            let app_handle2 = app_handle.clone();
            thread::spawn(move || {
                trigger_startup_flow(app_handle2, true);
            });
            return;
        }
    }

    // 普通异常退出上报。注：窗口正常关闭的停机流程与本 watcher 存在毫秒级竞态
    // （shutdown 仅 try_wait 轮询不消费 child，watcher 可能抢先观测到正常退出码并
    // 多 emit 一次 error），但彼时窗口已隐藏且应用随即退出，无用户可见影响
    emit_status(
        "error",
        Some(&format!("Java 服务异常退出 (退出码: {:?})，请查看日志排查问题。", status.code())),
    );
    STARTUP_RUNNING.store(false, Ordering::SeqCst);
}

/// 常驻子进程退出观测线程：服务就绪后以 500ms 间隔轮询子进程退出状态，
/// 接管健康轮询线程消亡后的观测职责。应用升级（88）与崩溃重启均由此驱动；
/// EXIT_WATCHING 防重复挂载，观测到退出即复位标志（分派内部可重入启动流程再挂新 watcher）
fn spawn_exit_watcher(app_handle: &tauri::AppHandle) {
    if EXIT_WATCHING.swap(true, Ordering::SeqCst) {
        return;
    }
    let app_handle = app_handle.clone();
    thread::spawn(move || {
        loop {
            thread::sleep(Duration::from_millis(500));
            let process_state = app_handle.state::<Arc<Mutex<ProcessManager>>>();
            let exited = {
                let mut mgr = process_state.lock().unwrap();
                mgr.check_child_exited()
            };
            if let Some(status) = exited {
                // 观测到退出：重置防重标志后分派（分派内部可能重入启动流程并再次挂 watcher）
                EXIT_WATCHING.store(false, Ordering::SeqCst);
                handle_child_exit(&app_handle, &process_state, status);
                return;
            }
        }
    });
}

fn resolve_ui_url() -> String {
    // 显式指定开发模式环境变量，直接使用 9662 前端开发服务器端口
    if std::env::var("ST_CUTE_DESKTOP_DEV").as_deref() == Ok("1") {
        return "http://localhost:9662".to_string();
    }

    // Debug 调试构建下，若检测到 9662 前端 Vite 开发服务器已启动，则自动复用 9662
    #[cfg(debug_assertions)]
    {
        use std::net::{SocketAddr, TcpStream};
        let addr: SocketAddr = "127.0.0.1:9662".parse().unwrap();
        if TcpStream::connect_timeout(&addr, Duration::from_millis(200)).is_ok() {
            return "http://localhost:9662".to_string();
        }
    }

    // 默认或打包生产模式：由 9661 后端服务统一承载前端静态资源
    "http://localhost:9661".to_string()
}

fn navigate_to_main(app: &tauri::AppHandle) {
    let target_url = resolve_ui_url();
    let window = app.get_webview_window("main")
        .or_else(|| app.webview_windows().into_values().next());

    if let Some(window) = window {
        if let Ok(url) = target_url.parse::<tauri::Url>() {
            let _ = window.navigate(url);
        } else {
            let _ = window.eval(&format!("window.location.replace('{}')", target_url));
        }
        let _ = window.show();
        let _ = window.set_focus();
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let process_mgr = Arc::new(Mutex::new(ProcessManager::new()));
    let process_mgr_clone = Arc::clone(&process_mgr);

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(
            tauri_plugin_window_state::Builder::default()
                // 仅持久化：大小、最大化。位置刻意不持久化，窗口始终居中显示；
                // 同时排除 VISIBLE —— 秒关流程会先 hide() 再退出，若保存 visible
                // 存在把 visible=false 写入状态文件导致下次启动不显示的竞态风险
                .with_state_flags(StateFlags::SIZE | StateFlags::MAXIMIZED)
                // 禁用插件创建后的自动异步恢复（时机太晚、无法控制亮相顺序），
                // 改由下方 setup 钩子在窗口隐藏阶段同步恢复，杜绝最大化闪变
                .skip_initial_state("main")
                .build(),
        )
        .manage(process_mgr)
        .invoke_handler(tauri::generate_handler![open_log_dir, retry_start, app_ready])
        .setup(move |app| {
            // 启动 desktop 日志每日清理守护线程（长驻不重启场景的过期日志兜底清理）
            logging::spawn_daily_cleanup_thread();

            // 窗口以隐藏方式创建（tauri.conf.json 中 visible=false），
            // 先在隐藏状态同步恢复上次的大小与最大化，再居中/亮相，
            // 保证用户看到的第一帧即为最终形态，避免尺寸跳变闪烁
            if let Some(window) = app.get_webview_window("main") {
                let restore_flags = StateFlags::SIZE | StateFlags::MAXIMIZED;
                let restored = window.restore_state(restore_flags).is_ok();
                if !restored || !window.is_maximized().unwrap_or(false) {
                    // 位置不持久化：未处于最大化时始终居中显示
                    let _ = window.center();
                }
                let _ = window.show();
                let _ = window.set_focus();
            }

            let app_handle = app.handle().clone();
            trigger_startup_flow(app_handle, false);
            Ok(())
        })
        .on_window_event(move |window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                // 1. 立即拦截并隐藏窗口，给用户秒关的视觉体验 (Hide-then-Shutdown)
                api.prevent_close();
                let _ = window.hide();

                let mgr_arc = Arc::clone(&process_mgr_clone);
                let app_handle = window.app_handle().clone();

                // 2. 后台异步执行优雅停机
                thread::spawn(move || {
                    let mut mgr = mgr_arc.lock().unwrap();
                    if mgr.spawned {
                        let token = mgr.token.clone();
                        shutdown::shutdown_service(BASE_URL, &token, &mut mgr.child);
                    }
                    app_handle.exit(0);
                });
            }
        })
        .run(tauri::generate_context!())
        .expect("运行 st-cute 桌面端程序失败");
}
