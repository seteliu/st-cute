use std::process::Child;
use std::thread::sleep;
use std::time::{Duration, Instant};

/// 编排后端服务的优雅停机流程
/// 1. 发起 POST /api/shutdown (携带桌面端凭证请求头)
/// 2. 轮询等待子进程退出（上限 5 秒）
/// 3. 超时则通过原生系统 API (child.kill) 强杀兜底，杜绝外部命令行窗口闪烁
pub fn shutdown_service(base_url: &str, token: &str, child_opt: &mut Option<Child>) {
    let shutdown_url = format!("{}/api/shutdown", base_url.trim_end_matches('/'));

    // 1. 先发起带桌面端凭证的停机请求，确认凭证仍有效（请求先于删文件，
    //    避免后端监视线程抢先销毁凭证导致本次停机被 403 拒绝）
    let _ = ureq::post(&shutdown_url)
        .set("X-Desktop-Token", token)
        .timeout(Duration::from_millis(1500))
        .call();

    // 2. 请求发出后再删除凭证文件：无论后端是否成功受理，
    //    监视线程感知后都会销毁内存凭证，杜绝停机窗口期被第三方复用凭证
    crate::process::delete_desktop_token_file();

    // 3. 快速轮询等待子进程自然退出
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
