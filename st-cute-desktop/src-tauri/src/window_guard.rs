//! 窗口越界自愈：保证窗口的标题栏（连同最小化/最大化/关闭按钮）始终留在当前显示器的工作区内。
//!
//! 【要解决的问题】
//! 在大屏上把窗口拉大后，若改用更小的分辨率（远程桌面以小分辨率接入、把窗口移到更小的显示器），
//! 工作区随之变小而窗口仍保持原有物理尺寸与位置，标题栏整条被推到屏幕外 ——
//! 用户既看不见按钮也无法拖动窗口自救。
//!
//! 【为什么不用定时轮询】
//! 该场景本身是低频事件，定期轮询既无必要也浪费。Tauri 2 只对外暴露
//! Resized / Moved / ScaleFactorChanged，未暴露 Windows 的 WM_DISPLAYCHANGE，
//! 而「分辨率变化但 DPI 不变」时 ScaleFactorChanged 未必触发，无法靠现有事件兜住。
//! 因此改用「用户回来看窗口」这个语义明确的时刻：启动恢复后一次 + 窗口重新获得聚焦时一次。
//!
//! 【已知边界】若分辨率变化时本窗口始终是前景窗口、焦点从未离开，则不会产生焦点跳变，
//! 本次自愈不会触发，窗口需等下一次失焦再聚焦（或重启）才复原。这是刻意不引入轮询的代价；
//! 若日后需要即时兜住，应监听 WM_DISPLAYCHANGE（需 unsafe 子类化），而非加回轮询。
//!
//! 【边界约定】
//! 1. 不调整最小尺寸约束：最小尺寸约束顶住时窗口可能缩不进工作区，此处不强行解除；
//! 2. 最大化 / 最小化 / 全屏状态一律跳过：这几种形态由系统接管尺寸，强行 set_size 会破坏语义；
//! 3. 位置纠正只在「尺寸确实发生收缩」时执行：仅切换分辨率而未改变窗口尺寸时保留用户原本的摆放位置；
//! 4. 收缩后的尺寸由 window-state 插件正常持久化，不做额外的「意愿尺寸」备份。

use crate::logging;
use std::sync::atomic::{AtomicBool, Ordering};
use tauri::{PhysicalPosition, PhysicalSize, Size, Window};

/// 需要自愈的窗口标签：仅主窗口参与，其余窗口（未来的设置窗、独立预览窗等）不介入
const MAIN_WINDOW_LABEL: &str = "main";

/// 自愈执行中的重入标志：set_size / set_position 会触发 Resized / Moved 回调，
/// 聚焦事件也可能在回调链中被再次投递，用该标志确保同一时刻只执行一次自愈动作
static FITTING: AtomicBool = AtomicBool::new(false);

/// 上一次观测到的窗口聚焦态：用于把连续投递的 Focused(true) 去重，
/// 保证只有「非聚焦 → 聚焦」这一跳变才触发自愈
static WAS_FOCUSED: AtomicBool = AtomicBool::new(false);

/// 聚焦事件入口：仅在目标窗口「由非聚焦转为聚焦」时执行自愈，并维护聚焦态记忆。
///
/// 去重放在这里而非调用方：Tao 的 Focused(true) 在窗口激活、从最小化恢复、
/// 点击任务栏等路径下可能重复投递，逐次执行自愈会带来无谓的窗口抖动。
/// 聚焦态记忆是模块级单例，故先按标签过滤，避免未来多窗口场景下互相串味。
pub fn on_window_focus_changed(window: &Window, focused: bool) {
    if window.label() != MAIN_WINDOW_LABEL {
        return;
    }
    // 先取旧值再更新，得到本次是否为「非聚焦 → 聚焦」的跳变
    let was_focused = WAS_FOCUSED.swap(focused, Ordering::SeqCst);
    if focused && !was_focused {
        ensure_window_in_work_area(window);
    }
}

/// 确保窗口落在当前显示器的工作区内（幂等，无越界时不做任何改动）。
///
/// 处理顺序：
/// 1. 跳过最大化 / 最小化 / 全屏；
/// 2. 取窗口所在显示器（取不到回退主显示器，再取不到直接放弃）；
/// 3. 客户区尺寸超出工作区可容纳范围 → 收缩（预留边框差值）；
/// 4. 仅当第 3 步确实收缩过，才以最小位移把窗口推回工作区内。
///
/// 第 4 步的钳制已保证窗口左上角落在工作区内，标题栏必然可见，无需额外的居中兜底。
/// 窗口因最小尺寸约束顶住而无法完全收缩时，钳制会把顶边对齐工作区顶部，
/// 标题栏依旧可见，用户仍可通过最大化按钮恢复操作。
pub fn ensure_window_in_work_area(window: &Window) {
    if window.label() != MAIN_WINDOW_LABEL {
        return;
    }

    // 1. 系统接管尺寸的形态不干预
    if window.is_maximized().unwrap_or(false)
        || window.is_minimized().unwrap_or(false)
        || window.is_fullscreen().unwrap_or(false)
    {
        return;
    }

    // 防重入：已在自愈流程中（由 set_size / set_position 引发的回调链）直接返回
    if FITTING.swap(true, Ordering::SeqCst) {
        return;
    }
    // 实际逻辑拆到独立函数，保证其提前返回时本处仍能统一复位标志
    let result = fit_in_work_area(window);
    FITTING.store(false, Ordering::SeqCst);

    if let Err(err) = result {
        logging::append_desktop_log(&format!("[window-guard] 窗口越界自愈失败: {}", err));
    }
}

/// 越界自愈的实际执行体，独立成函数便于统一复位重入标志
fn fit_in_work_area(window: &Window) -> tauri::Result<()> {
    // 2. 定位窗口当前所在显示器：取不到时回退主显示器（跨平台兜底，Windows 下必然有值）
    let monitor = match window.current_monitor()? {
        Some(monitor) => monitor,
        None => match window.primary_monitor()? {
            Some(monitor) => monitor,
            None => return Ok(()),
        },
    };

    // 工作区（显示器尺寸扣除任务栏等）——判定全程使用物理像素，规避 DPI 换算误差
    let work_area = monitor.work_area();
    let area_left = work_area.position.x;
    let area_top = work_area.position.y;
    let area_right = area_left + work_area.size.width as i32;
    let area_bottom = area_top + work_area.size.height as i32;

    // 3. 尺寸收缩：Tauri 的 set_size 作用于客户区（inner），故先算出边框（外框 - 客户区）
    //    差值并扣除，避免「按工作区尺寸设置客户区」后外框反而比工作区更大
    let outer_size = window.outer_size()?;
    let inner_size = window.inner_size()?;
    let frame_width = outer_size.width.saturating_sub(inner_size.width);
    let frame_height = outer_size.height.saturating_sub(inner_size.height);
    // 工作区能容纳的最大客户区尺寸；下限取 1 是为了避免 saturating 下溢为 0 后 set_size(0)
    let max_inner_width = work_area.size.width.saturating_sub(frame_width).max(1);
    let max_inner_height = work_area.size.height.saturating_sub(frame_height).max(1);

    let target_width = inner_size.width.min(max_inner_width);
    let target_height = inner_size.height.min(max_inner_height);
    let shrunk = target_width != inner_size.width || target_height != inner_size.height;
    if shrunk {
        window.set_size(Size::Physical(PhysicalSize::new(
            target_width,
            target_height,
        )))?;
    }

    // 4. 位置纠正：只在尺寸确实收缩过时执行 —— 用户原本的摆放位置（含刻意半出屏）保持不变，
    //    避免仅仅切换显示器分辨率就被强行挪动窗口。
    //    用「目标客户区尺寸 + 边框」推算收缩后的外框，而非 set_size 后立即回读：
    //    尺寸生效由系统消息异步完成，回读可能拿到旧值。推算值即使因最小尺寸约束顶住而偏小，
    //    偏差只体现在右边/下边（窗口可能略微超界），左上角与标题栏仍保证可见。
    if !shrunk {
        return Ok(());
    }

    let planned_outer_width = (target_width + frame_width).min(work_area.size.width);
    let planned_outer_height = (target_height + frame_height).min(work_area.size.height);
    let outer_pos = window.outer_position()?;
    let max_x = (area_right - planned_outer_width as i32).max(area_left);
    let max_y = (area_bottom - planned_outer_height as i32).max(area_top);
    let fitted_x = outer_pos.x.clamp(area_left, max_x);
    let fitted_y = outer_pos.y.clamp(area_top, max_y);

    if fitted_x != outer_pos.x || fitted_y != outer_pos.y {
        window.set_position(PhysicalPosition::new(fitted_x, fitted_y))?;
    }

    Ok(())
}
