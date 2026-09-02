use crate::logging;
use rand::distributions::Alphanumeric;
use rand::Rng;
use std::env;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};

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

/// 生成 32 位随机字符停机鉴权凭证
pub fn generate_token() -> String {
    rand::thread_rng()
        .sample_iter(&Alphanumeric)
        .take(32)
        .map(char::from)
        .collect()
}

pub struct ProcessManager {
    pub spawned: bool,
    pub child: Option<Child>,
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
            token: generate_token(),
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
        let java_path = Self::resolve_java_path();
        let jar_path = Self::resolve_jar_path();

        let log_file = logging::open_log_file()?;
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
            .env("ST_CUTE_SHUTDOWN_TOKEN", &self.token)
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
