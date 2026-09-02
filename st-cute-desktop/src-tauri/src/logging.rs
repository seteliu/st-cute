use chrono::Local;
use std::fs::{create_dir_all, read_dir, remove_file, File, OpenOptions};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

/// 获取应用日志存储目录 `%USERPROFILE%\.st-cute\logs\`
pub fn get_log_dir() -> PathBuf {
    if let Some(home) = dirs::home_dir() {
        home.join(".st-cute").join("logs")
    } else {
        PathBuf::from(".st-cute").join("logs")
    }
}

/// 清理超过指定天数的旧日志文件（默认 7 天）
fn cleanup_old_logs(log_dir: &Path, max_days: u64) {
    let now = SystemTime::now();
    let max_duration = Duration::from_secs(max_days * 24 * 60 * 60);

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

            // 仅清理以 service 开头且以 .log 结尾的日志文件
            if file_name.starts_with("service") && file_name.ends_with(".log") {
                if let Ok(metadata) = path.metadata() {
                    if let Ok(modified) = metadata.modified() {
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

/// 获取当天的日志文件路径（例如 `%USERPROFILE%\.st-cute\logs\service_2026-09-01.log`）
pub fn get_log_file_path() -> Result<PathBuf, String> {
    let log_dir = get_log_dir();
    create_dir_all(&log_dir).map_err(|e| format!("创建日志目录失败: {}", e))?;

    // 启动时自动清理 7 天前的过期日志
    cleanup_old_logs(&log_dir, 7);

    let today_str = Local::now().format("service_%Y-%m-%d.log").to_string();
    Ok(log_dir.join(today_str))
}

/// 打开/创建用于输出重定向的当天日志文件（追加模式）
pub fn open_log_file() -> Result<File, String> {
    let path = get_log_file_path()?;
    OpenOptions::new()
        .create(true)
        .write(true)
        .append(true)
        .open(&path)
        .map_err(|e| format!("打开日志文件失败 ({}): {}", path.display(), e))
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
