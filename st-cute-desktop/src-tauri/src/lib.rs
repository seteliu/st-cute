mod logging;
mod probe;
mod process;
mod shutdown;

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

        // 1. 初次探测：检查是否已有外部运行实例
        let probe_result = probe::probe_ping(BASE_URL);
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
        }

        // 3. 高频自适应轮询健康状态（最多等待 60 秒）
        let start_time = Instant::now();
        let timeout = Duration::from_secs(60);

        while start_time.elapsed() < timeout {
            // 检查子进程是否已中途崩溃
            {
                let mut mgr = process_state.lock().unwrap();
                if let Some(status) = mgr.check_child_exited() {
                    emit_status(
                        "error",
                        Some(&format!("Java 服务异常退出 (退出码: {:?})，请查看日志排查问题。", status.code())),
                    );
                    STARTUP_RUNNING.store(false, Ordering::SeqCst);
                    return;
                }
            }

            // 快速探测服务是否已响应
            if let probe::ProbeResult::Ready { .. } = probe::probe_ping(BASE_URL) {
                emit_status("ready", None);
                navigate_to_main(&app_handle);
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
