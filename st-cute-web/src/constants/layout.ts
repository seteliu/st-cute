/**
 * 三栏布局（左会话栏 / 中聊天区 / 右审查栏）宽度约束的单一来源
 *
 * 【为什么需要它】
 * 中间聊天区是 flex 行布局中唯一可被压缩的一栏：左右两个 n-layout-sider 自带 flex-shrink: 0
 * 且 .center-content 显式设了 min-width: 0（见 assets/styles/main.css）。若左右侧栏各自
 * 按固定上限无限拖宽（左 500 + 右 600 = 1100），中间栏会被挤压到内部控件换行、发送按钮被裁切。
 * 故左右拖拽上限改为动态推导：窗口宽 - 对侧侧栏记忆宽度 - 中间聊天区最小宽度
 * （对侧一律取记忆宽度而非折叠态的有效占位宽，契约详见 clampSiderWidth 注释）。
 *
 * 维护须知：新增加减侧栏宽度的逻辑时，一律引用本文件常量与 clampSiderWidth，禁止再写裸数字。
 */

/** 中间聊天区最小宽度（px）：保证输入区底行控件单行排布且留有呼吸感，左右拖拽上限据此推导 */
export const CHAT_MIN_WIDTH = 680

/** 左右侧栏的默认宽度（px）：store 初始值与本文件的断点推导共用同一来源 */
export const DEFAULT_LEFT_SIDER_WIDTH = 280
export const DEFAULT_RIGHT_SIDER_WIDTH = 291

/** 移动端抽屉的固定宽度（px）：移动端抽屉不参与拖拽记忆，恒用固定宽度 */
export const MOBILE_DRAWER_LEFT_WIDTH = 280
export const MOBILE_DRAWER_RIGHT_WIDTH = 320

/**
 * 窄桌面断点（px）：窗口宽低于该值时三栏无法共存，左右侧栏退化为悬浮 Drawer 抽屉（默认折叠），
 * 中间聊天区独占全宽；移动端断点（768）之上、本断点之下的区间称为「窄桌面档」，
 * 此时仅侧栏容器抽屉化，中间区仍走桌面渲染（不套移动端 UI）。
 *
 * 由「两侧默认宽度之和 + 中间最小宽度」派生，故调整上述任一常量时本断点自动跟随
 */
export const NARROW_DESKTOP_BREAKPOINT =
  DEFAULT_LEFT_SIDER_WIDTH + DEFAULT_RIGHT_SIDER_WIDTH + CHAT_MIN_WIDTH

/** 左侧会话栏宽度区间（px） */
export const LEFT_SIDER_MIN_WIDTH = 240
export const LEFT_SIDER_MAX_WIDTH = 500

/** 右侧审查栏宽度区间（px） */
export const RIGHT_SIDER_MIN_WIDTH = 240
export const RIGHT_SIDER_MAX_WIDTH = 600

/** 侧栏方位标识 */
export type SiderSide = 'left' | 'right'

/** 各侧栏自身的宽度硬区间（与窗口无关的业务上下限） */
export const SIDER_WIDTH_LIMITS: Record<SiderSide, { min: number; max: number }> = {
  left: { min: LEFT_SIDER_MIN_WIDTH, max: LEFT_SIDER_MAX_WIDTH },
  right: { min: RIGHT_SIDER_MIN_WIDTH, max: RIGHT_SIDER_MAX_WIDTH }
}

/**
 * 将侧栏的期望宽度钳制到合法区间，并确保中间聊天区的最小宽度不被对侧侧栏侵占。
 *
 * <p>
 * 上限取「本侧硬上限」与「窗口宽 - 对侧侧栏宽 - CHAT_MIN_WIDTH」的较小者，
 * 拖到该值即停住（不产生横向溢出、不裁切任何一栏）。
 * </p>
 *
 * <p>
 * 【调用契约】otherSiderWidth 必须传对侧侧栏的<b>记忆宽度</b>（appStore 中存的值），
 * 而非「当前有效占位宽度」——禁止因对侧处于折叠态就传 0。折叠只是临时不占位，
 * 用户随时可能重新展开；若按折叠态放宽上限，展开瞬间中间聊天区就会被挤压变形。
 * </p>
 *
 * <p>
 * 若窗口本身过窄，连本侧最小宽度都放不下（推导上限低于硬下限），则退回硬下限——
 * 此时中间栏已无法保住 CHAT_MIN_WIDTH，属于窗口尺寸的物理限制（各侧栏硬下限不可再让）。
 * </p>
 *
 * @param side 拖拽中的侧栏方位
 * @param desiredWidth 期望宽度（由鼠标位置换算而来）
 * @param otherSiderWidth 对侧侧栏的记忆宽度（非折叠态的有效宽度）
 * @param viewportWidth 视口宽度，默认取当前窗口宽度（显式传入便于单测）
 * @returns 钳制后的宽度（px）
 */
export const clampSiderWidth = (
  side: SiderSide,
  desiredWidth: number,
  otherSiderWidth: number,
  viewportWidth: number = window.innerWidth
): number => {
  const { min, max } = SIDER_WIDTH_LIMITS[side]
  const available = viewportWidth - otherSiderWidth - CHAT_MIN_WIDTH
  const upperBound = Math.max(min, Math.min(max, available))
  return Math.min(Math.max(desiredWidth, min), upperBound)
}
