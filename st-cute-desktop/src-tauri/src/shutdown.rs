use std::process::Child;
use std::thread::sleep;
use std::time::{Duration, Instant};

/// 编排后端服务的优雅停机流程
/// 1. 发起 POST /api/shutdown (携带 Token)
/// 2. 轮询等待子进程退出（上限 5 秒）
/// 3. 超时则通过原生系统 API (child.kill) 强杀兜底，杜绝外部命令行窗口闪烁
pub fn shutdown_service(base_url: &str, token: &str, child_opt: &mut Option<Child>) {
    let shutdown_url = format!("{}/api/shutdown", base_url.trim_end_matches('/'));

    // 1. 发起带 Token 的停机请求
    let _ = ureq::post(&shutdown_url)
        .set("X-Shutdown-Token", token)
        .timeout(Duration::from_millis(1500))
        .call();

    // 2. 快速轮询等待子进程自然退出
    let start = Instant::now();
    let max_wait = Duration::from_secs(5);

    if let Some(ref mut child) = child_opt {
        while start.elapsed() < max_wait {
            if let Ok(Some(_)) = child.try_wait() {
                return; // 子进程已正常退出
            }
            sleep(Duration::from_millis(50));
        }

        // 3. 超时强杀兜底（纯系统 API，不拉起外部控制台进程，零黑框弹窗）
        let _ = child.kill();
        let _ = child.wait();
    }
}
