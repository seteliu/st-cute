use std::fs;
use std::path::{Path, PathBuf};

/// 桌面壳 jar 升级接力模块
///
/// 职责：观测子进程以退出码 88 退出后，完成新包就位（swap）与失败回滚（rollback）。
/// 全部操作均发生在 Java 进程已退出（文件锁已释放）之后，可安全执行文件替换。
///
/// 路径布局（与 ProcessManager::resolve_jar_path 的探测优先级对齐）：
/// - jar 在位：`<exe_dir>/resources/app.jar`
/// - 待装新包：`<exe_dir>/resources/updates/app.jar.new`
/// - 回滚备份：`<exe_dir>/resources/updates/app.jar.bak`
///
/// 注意备份刻意放在 updates/ 目录而非 resources/ 根：resources/app.jar.bak 会被
/// tauri.conf.json 的 bundle.resources 打包声明意外携带。
pub struct JarRelay {
    jar_path: PathBuf,
    pending_path: PathBuf,
    backup_path: PathBuf,
}

impl JarRelay {
    pub fn new() -> Self {
        let exe_dir = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .unwrap_or_else(|| PathBuf::from("."));
        let resources = exe_dir.join("resources");
        Self {
            jar_path: resources.join("app.jar"),
            pending_path: resources.join("updates").join("app.jar.new"),
            backup_path: resources.join("updates").join("app.jar.bak"),
        }
    }

    /// 是否存在待就位的新包（Java 侧退出码 88 的前置约定）
    pub fn has_pending(&self) -> bool {
        self.pending_path.exists()
    }

    /// 是否存在可回滚的备份（新版启动失败时的一次性回退依据）
    pub fn has_backup(&self) -> bool {
        self.backup_path.exists()
    }

    /// 新包就位：jar → .bak 备份，.new → jar
    ///
    /// 调用前提：Java 子进程已退出（jar 文件锁已释放）。
    /// Windows 下子进程退出后 OS 句柄释放存在短暂延迟，rename 可能瞬时失败：
    /// 短退避重试后再退化为 copy+delete（copy 写入新句柄不受旧句柄影响）
    pub fn swap(&self) -> Result<(), String> {
        let _ = fs::create_dir_all(self.pending_path.parent().unwrap_or(Path::new(".")));
        // 清理历史备份：每次交换前重置，备份始终对应"上一个正常运行的版本"
        if self.backup_path.exists() {
            if let Err(e) = fs::remove_file(&self.backup_path) {
                return Err(format!("清理旧备份失败: {}", e));
            }
        }
        if self.jar_path.exists() {
            rename_with_backoff(&self.jar_path, &self.backup_path)
                .map_err(|e| format!("备份当前 jar 失败: {}", e))?;
        }
        rename_with_backoff(&self.pending_path, &self.jar_path)
            .map_err(|e| format!("新包就位失败: {}", e))
    }

    /// 回滚：.bak → jar（新版启动失败时的一次性回退）
    ///
    /// 调用前提：Java 子进程已退出
    pub fn rollback(&self) -> Result<(), String> {
        if !self.backup_path.exists() {
            return Err("备份不存在，无法回滚".to_string());
        }
        if self.jar_path.exists() {
            if let Err(e) = fs::remove_file(&self.jar_path) {
                return Err(format!("移除失败 jar 失败: {}", e));
            }
        }
        fs::rename(&self.backup_path, &self.jar_path)
            .map_err(|e| format!("回滚 jar 失败: {}", e))
    }
}

/// 启动成功后的收尾：清理备份与残留待装包（升级闭环确认后调用）
pub fn cleanup_after_success() {
    let relay = JarRelay::new();
    if relay.has_backup() {
        if let Err(e) = fs::remove_file(&relay.backup_path) {
            eprintln!("[update] 清理升级备份失败: {}", e);
        }
    }
    if relay.has_pending() {
        if let Err(e) = fs::remove_file(&relay.pending_path) {
            eprintln!("[update] 清理残留待装包失败: {}", e);
        }
    }
}

/// 带退避重试的文件改名：Windows 下进程刚退出时句柄延迟释放会让 rename 瞬时失败，
/// 重试三次（50ms/200ms/500ms）后仍失败则退化为 copy+delete（写入不受旧句柄影响）
fn rename_with_backoff(from: &Path, to: &Path) -> std::io::Result<()> {
    let delays = [50u64, 200, 500];
    let mut last_err = None;
    for delay in &delays {
        match fs::rename(from, to) {
            Ok(()) => return Ok(()),
            Err(e) => {
                last_err = Some(e);
                std::thread::sleep(std::time::Duration::from_millis(*delay));
            }
        }
    }
    // 兜底退化：复制到目标后删源（目标已存在的场景由调用方保证已清理）
    match fs::copy(from, to).map(|_| ()) {
        Ok(()) => fs::remove_file(from),
        Err(copy_err) => Err(last_err.unwrap_or(copy_err)),
    }
}
