use crate::logging;
use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::thread::sleep;
use std::time::{Duration, Instant};

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

#[cfg(target_os = "windows")]
mod win_job {
    use std::os::windows::io::AsRawHandle;
    use std::process::Child;
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    pub struct JobObject {
        handle: HANDLE,
    }

    // Windows 平台安全多线程传递
    unsafe impl Send for JobObject {}
    unsafe impl Sync for JobObject {}

    impl JobObject {
        pub fn create() -> Result<Self, String> {
            unsafe {
                let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                if handle.is_null() || handle == INVALID_HANDLE_VALUE {
                    return Err("创建 Windows Job Object 失败".to_string());
                }

                let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;

                let res = SetInformationJobObject(
                    handle,
                    JobObjectExtendedLimitInformation,
                    &info as *const _ as *const _,
                    std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                );

                if res == 0 {
                    CloseHandle(handle);
                    return Err("配置 Job Object KILL_ON_JOB_CLOSE 失败".to_string());
                }

                Ok(Self { handle })
            }
        }

        pub fn assign_child(&self, child: &Child) -> Result<(), String> {
            unsafe {
                let raw_handle = child.as_raw_handle() as HANDLE;
                let res = AssignProcessToJobObject(self.handle, raw_handle);
                if res == 0 {
                    return Err("将子进程分配至 Job Object 失败".to_string());
                }
                Ok(())
            }
        }
    }

    impl Drop for JobObject {
        fn drop(&mut self) {
            unsafe {
                if !self.handle.is_null() && self.handle != INVALID_HANDLE_VALUE {
                    CloseHandle(self.handle);
                }
            }
        }
    }
}

/// 桌面端凭证文件路径：~/.st-cute/.desktop-token（由后端托管模式启动时原子写入）
fn desktop_token_path() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".st-cute")
        .join(".desktop-token")
}

/// 轮询读取后端落盘的桌面端凭证
/// 后端原子写入存在短暂时间窗口，读到空内容或过短内容时按未就绪处理并短暂退避重试，
/// 最长等待 10 秒，超时返回 None 交由调用方决定失败策略
pub fn read_desktop_token() -> Option<String> {
    let path = desktop_token_path();
    let deadline = Instant::now() + Duration::from_secs(10);

    while Instant::now() < deadline {
        if let Ok(content) = fs::read_to_string(&path) {
            let token = content.trim().to_string();
            // 后端生成的凭证固定为 43 字符 Base64url，过短视为半写状态继续等待
            if token.len() >= 32 {
                return Some(token);
            }
        }
        sleep(Duration::from_millis(100));
    }
    None
}

/// 主动删除桌面端凭证文件：后端监视线程感知后立即销毁内存凭证并关闭停机通道
pub fn delete_desktop_token_file() {
    let path = desktop_token_path();
    if path.exists() {
        if let Err(e) = fs::remove_file(&path) {
            // 删除失败不阻塞停机编排：后端凭证销毁另有停机受理与强杀兜底双保险
            eprintln!("删除桌面端凭证文件失败: {}", e);
        }
    }
}

pub struct ProcessManager {
    pub spawned: bool,
    pub child: Option<Child>,
    /// 桌面端凭证：托管模式下自凭证文件轮询读取填充，空串表示尚未取得
    pub token: String,
    #[cfg(target_os = "windows")]
    job_object: Option<win_job::JobObject>,
}

impl ProcessManager {
    pub fn new() -> Self {
        #[cfg(target_os = "windows")]
        let job = win_job::JobObject::create().ok();

        Self {
            spawned: false,
            child: None,
            // 凭证不再由壳生成：托管模式下由后端生成落盘，启动流程中轮询读取填充
            token: String::new(),
            #[cfg(target_os = "windows")]
            job_object: job,
        }
    }

    /// 探测可用的 java 可执行程序路径
    fn resolve_java_path() -> PathBuf {
        let exe_dir = env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .unwrap_or_else(|| PathBuf::from("."));

        // 优先级 1: 安装包资源目录 (resources/jre/bin/java.exe)
        let embedded_jre = exe_dir.join("resources").join("jre").join("bin").join("java.exe");
        if embedded_jre.exists() {
            return embedded_jre;
        }

        // 优先级 2: 当前工作目录下 resources/jre/bin/java.exe
        let cwd_jre = PathBuf::from("resources").join("jre").join("bin").join("java.exe");
        if cwd_jre.exists() {
            return cwd_jre;
        }

        // 优先级 3: 系统 PATH 中的 java
        PathBuf::from("java")
    }

    /// 探测可用的 app.jar 路径
    fn resolve_jar_path() -> PathBuf {
        let exe_dir = env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .unwrap_or_else(|| PathBuf::from("."));

        // 优先级 1: 安装包资源目录 (resources/app.jar)
        let embedded_jar = exe_dir.join("resources").join("app.jar");
        if embedded_jar.exists() {
            return embedded_jar;
        }

        // 优先级 2: 当前工作目录下 resources/app.jar
        let cwd_jar = PathBuf::from("resources").join("app.jar");
        if cwd_jar.exists() {
            return cwd_jar;
        }

        // 优先级 3: 当前目录下的 app.jar
        PathBuf::from("app.jar")
    }

    /// 启动托管 Java 后端服务
    pub fn spawn_service(&mut self) -> Result<(), String> {
        // 先清理上次异常退出可能残留的旧凭证文件：防止本次启动流程
        // 在后端写入新凭证之前误读到过期凭证，导致后续停机请求恒被拒绝
        delete_desktop_token_file();

        let java_path = Self::resolve_java_path();
        let jar_path = Self::resolve_jar_path();

        // 早期报错兜底：显式校验 java 与 jar 路径存在，缺失时直接返回可读的失败原因。
        // 生产壳为 GUI 子系统（无控制台），JVM 因路径错误产生的早期报错只会流入
        // desktop 早期报错文件，在壳侧提前拦截比让用户事后翻日志更友好
        if !java_path.exists() {
            return Err(format!("未找到 Java 运行时: {}", java_path.display()));
        }
        if !jar_path.exists() {
            return Err(format!("未找到后端应用包: {}", jar_path.display()));
        }

        // 早期报错日志：仅承接 JVM 早期启动报错（desktop_YYYY-MM-DD.log）；
        // 原生 service 日志由后端 logback 自行落盘与轮换清理，壳侧不再转发
        let log_file = logging::open_desktop_log_file()?;
        let err_file = log_file.try_clone().map_err(|e| format!("复制日志句柄失败: {}", e))?;

        let mut cmd = Command::new(&java_path);

        // JVM 内存参数：优先读取 ST_CUTE_JAVA_OPTS 环境变量，未设置时兜底默认值
        let java_opts = env::var("ST_CUTE_JAVA_OPTS")
            .unwrap_or_else(|_| "-Xms256m -Xmx1024m".to_string());

        cmd.arg("-Dfile.encoding=UTF-8")
            .arg("-Duser.language=zh")
            .arg("-Duser.country=CN")
            .arg("-Djava.awt.headless=true");

        // 按空白拆分为独立参数追加（整串作为单个参数会导致 JVM 无法识别）
        for opt in java_opts.split_whitespace() {
            cmd.arg(opt);
        }

        cmd.arg("-jar")
            .arg(&jar_path)
            // 托管模式标志：后端据此激活桌面端凭证机制（生成凭证并落盘 .desktop-token），
            // 同时后端日志子系统据此追加 desktop profile 关闭控制台 appender，仅写 service 日志文件。
            // stdout/stderr 保持重定向至 desktop 早期报错文件，仅承载 logback 接管前的 JVM 报错
            .env("ST_CUTE_DESKTOP_MANAGED", "1")
            .stdout(Stdio::from(log_file))
            .stderr(Stdio::from(err_file));

        // Windows 下使用 CREATE_NO_WINDOW 避免闪现黑框控制台
        #[cfg(target_os = "windows")]
        {
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }

        let child = cmd.spawn().map_err(|e| {
            format!(
                "拉起 Java 进程失败 (java: {}, jar: {}): {}",
                java_path.display(),
                jar_path.display(),
                e
            )
        })?;

        // 绑定至 Windows Job Object 确保退出时不留孤儿进程
        #[cfg(target_os = "windows")]
        {
            if let Some(ref job) = self.job_object {
                let _ = job.assign_child(&child);
            }
        }

        self.child = Some(child);
        self.spawned = true;
        Ok(())
    }

    /// 检查托管的子进程是否已非预期退出
    pub fn check_child_exited(&mut self) -> Option<std::process::ExitStatus> {
        if let Some(ref mut child) = self.child {
            match child.try_wait() {
                Ok(Some(status)) => Some(status),
                _ => None,
            }
        } else {
            None
        }
    }
}
