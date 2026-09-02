// Windows 发布模式下隐藏控制台窗口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    st_cute_desktop_lib::run();
}
