use chrono::Local;
use std::fs::{create_dir_all, metadata, read_dir, remove_file, File, OpenOptions};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, SystemTime};

/// 桌面壳早期报错日志单分片大小上限（20MB）
const MAX_FILE_SIZE: u64 = 20 * 1024 * 1024;
/// 桌面壳早期报错日志保留天数（超过即清理）
const MAX_AGE_DAYS: u64 = 7;
/// 单日分片序号扫描兜底上限（防病态场景无限循环）
const MAX_SHARDS: u32 = 1000;

/// 获取应用日志存储目录 `%USERPROFILE%\.st-cute\logs\`
pub fn get_log_dir() -> PathBuf {
    if let Some(home) = dirs::home_dir() {
        home.join(".st-cute").join("logs")
    } else {
        PathBuf::from(".st-cute").join("logs")
    }
}

/// 清理超过保留天数的过期 desktop 日志（按文件修改时间判断，每次打开日志前顺带执行）
fn cleanup_expired_desktop_logs(log_dir: &Path) {
    let now = SystemTime::now();
    let max_duration = Duration::from_secs(MAX_AGE_DAYS * 24 * 60 * 60);

    if let Ok(entries) = read_dir(log_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }

            let file_name = path
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or_default();

            // 仅清理壳自产的 desktop 早期报错日志（兼容清理旧的无分片命名）
            if file_name.starts_with("desktop") && file_name.ends_with(".log") {
                if let Ok(file_meta) = path.metadata() {
                    if let Ok(modified) = file_meta.modified() {
                        if let Ok(elapsed) = now.duration_since(modified) {
                            if elapsed > max_duration {
                                let _ = remove_file(&path);
                            }
                        }
                    }
                }
            }
        }
    }
}

/// 解析本次应追加的 desktop 日志分片路径：按 desktop_日期.序号.log 找第一个
/// 不存在或未满 20MB 的分片；写满即自然轮换至下一个序号
fn resolve_desktop_log_path(log_dir: &Path, today: &str) -> PathBuf {
    for index in 0..MAX_SHARDS {
        let candidate = log_dir.join(format!("desktop_{}.{}.log", today, index));
        match metadata(&candidate) {
            // 分片不存在 → 新开该分片
            Err(_) => return candidate,
            // 存在且未满上限 → 继续追加
            Ok(file_meta) if file_meta.len() < MAX_FILE_SIZE => return candidate,
            // 已满 20MB → 轮换至下一个序号
            Ok(_) => continue,
        }
    }
    // 分片数耗尽（理论极端场景）：复用最后一个分片继续追加，保证启动不失败
    log_dir.join(format!("desktop_{}.{}.log", today, MAX_SHARDS - 1))
}

/// 打开桌面壳早期报错日志文件（例如 `%USERPROFILE%\.st-cute\logs\desktop_2026-09-21.0.log`，追加模式）
/// 仅用于承接 JVM 早期启动报错（此阶段后端 logback 日志子系统尚未接管，报错无处显示）：
/// 原生 service 日志已由后端自行落盘并轮换清理，壳侧不再自建转发机制；
/// desktop 为壳自身产物文件，独立维护轻量规则：单分片 20MB 上限轮换，保留 7 天并自动清理过期文件
pub fn open_desktop_log_file() -> Result<File, String> {
    let log_dir = get_log_dir();
    create_dir_all(&log_dir).map_err(|e| format!("创建日志目录失败: {}", e))?;

    // 启动即清理 7 天前的过期 desktop 日志
    cleanup_expired_desktop_logs(&log_dir);

    let today = Local::now().format("%Y-%m-%d").to_string();
    let path = resolve_desktop_log_path(&log_dir, &today);
    OpenOptions::new()
        .create(true)
        .write(true)
        .append(true)
        .open(&path)
        .map_err(|e| format!("打开早期报错日志文件失败 ({}): {}", path.display(), e))
}

/// 启动每日清理守护线程：应用长驻不重启时，打开日志时的启动期清理不会再触发，
/// 由本线程每 24 小时兜底执行一次过期清理（按修改时间判断，执行时刻漂移无影响）
pub fn spawn_daily_cleanup_thread() {
    thread::spawn(|| loop {
        // 先睡后清：进程启动打开日志时已完成首次清理，线程只需负责后续周期
        thread::sleep(Duration::from_secs(24 * 60 * 60));
        cleanup_expired_desktop_logs(&get_log_dir());
    });
}

/// 使用系统默认文件管理器打开日志目录
pub fn open_log_dir() -> Result<(), String> {
    let dir = get_log_dir();
    create_dir_all(&dir).map_err(|e| format!("创建日志目录失败: {}", e))?;

    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer")
            .arg(dir.as_os_str())
            .spawn()
            .map_err(|e| format!("打开日志目录失败: {}", e))?;
        Ok(())
    }

    #[cfg(not(target_os = "windows"))]
    {
        opener::open(&dir).map_err(|e| format!("打开日志目录失败: {}", e))?;
        Ok(())
    }
}
