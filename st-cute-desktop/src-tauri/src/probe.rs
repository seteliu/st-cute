use serde::Deserialize;
use std::time::Duration;

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub enum ProbeResult {
    /// 端口已就绪且为 st-cute 服务
    Ready { app: String, version: String },
    /// 端口已被其他非 st-cute 服务占用
    PortConflict { detail: String },
    /// 服务未运行（连接被拒绝或超时）
    NotRunning,
}

#[derive(Deserialize)]
struct ApiResponse<T> {
    code: i32,
    data: Option<T>,
}

#[derive(Deserialize)]
struct PingData {
    app: Option<String>,
    version: Option<String>,
}

/// 发起 GET /api/ping 探测请求
pub fn probe_ping(base_url: &str) -> ProbeResult {
    let ping_url = format!("{}/api/ping", base_url.trim_end_matches('/'));

    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_millis(300))
        .timeout_read(Duration::from_millis(500))
        .build();

    match agent.get(&ping_url).call() {
        Ok(response) => {
            if let Ok(api_res) = response.into_json::<ApiResponse<PingData>>() {
                if api_res.code == 0 {
                    if let Some(data) = api_res.data {
                        if data.app.as_deref() == Some("st-cute") {
                            return ProbeResult::Ready {
                                app: "st-cute".to_string(),
                                version: data.version.unwrap_or_else(|| "unknown".to_string()),
                            };
                        }
                    }
                }
            }
            ProbeResult::PortConflict {
                detail: format!("端口正在响应，但服务标识不是 st-cute ({})", ping_url),
            }
        }
        Err(ureq::Error::Status(code, resp)) => {
            let body_str = resp.into_string().unwrap_or_default();
            if body_str.contains("未登录") || body_str.contains("st-cute") {
                ProbeResult::Ready {
                    app: "st-cute".to_string(),
                    version: "dev".to_string(),
                }
            } else {
                ProbeResult::PortConflict {
                    detail: format!("端口被其他服务占用 (HTTP 响应状态码: {})", code),
                }
            }
        }
        Err(ureq::Error::Transport(_)) => ProbeResult::NotRunning,
    }
}
