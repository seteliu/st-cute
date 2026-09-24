/**
 * 全局主题变量体系（Theme Variables）—— 多主题体系的单一事实来源
 *
 * 职责：
 * 1. 定义主题变量结构（色彩体系），dark 为默认主题，light 为预留扩展位
 * 2. applyTheme(): 把主题变量写入 CSS 自定义属性（document.documentElement），
 *    全部样式（main.css 与各组件 scoped 样式）统一引用 var(--xxx)，禁止再出现硬编码色值
 * 3. buildNaiveThemeOverrides(): 从同一份主题变量派生 Naive UI 主题覆盖，
 *    保证组件库与自绘样式永远同源同色，切主题时一处生效
 *
 * 扩展新主题：在 THEMES 中新增一份 ThemeVars 并在 ThemeName 联合类型登记即可。
 */

/** 主题变量结构：新增主题时必须填满全部字段（缺失即类型错误，防止半成品主题） */
export interface ThemeVars {
  /** 品牌主色（按钮/链接/高亮/图标着色） */
  primary: string
  primaryHover: string
  primaryPressed: string
  /** 品牌主色弱背景（选中项悬浮、批量管理激活底、待审批轻量胶囊底等） */
  primaryBgWeak: string
  /** 品牌主色深实底（侧边栏激活条目等） */
  primaryBgSolid: string
  /** 品牌辅助色（思考过程、高亮重点、AI 运行中动效等） */
  accent: string
  /** 品牌辅助色弱背景（AI 思考与运行中弱底） */
  accentBgWeak: string

  /** 背景体系：base 页面底色 → card 卡片 → cardActive 选中/激活 → elevated 浮层 → inset 凹陷内嵌区 */
  bgBase: string
  bgCard: string
  bgCardActive: string
  bgElevated: string
  /** 高海拔模态弹窗与大抽屉表面（拔出层） */
  bgModal: string
  bgInset: string
  /** 代码块等强对比深色背景 */
  bgCode: string

  /** 文本体系：bright 高亮标题 → base 正文 → muted 弱化说明 → faint 最弱提示 → onPrimary 主色背景上的文字 */
  textBright: string
  textBase: string
  textMuted: string
  textFaint: string
  textOnPrimary: string

  /** 边框体系 */
  borderBase: string
  borderActive: string

  /** 状态色（成功/错误/警告/信息）。注意：dark 主题的 success/warning 沿用历史蓝紫配色，
   *  语义徽章用的"成功绿 / 警告琥珀橙"由下方独立基准色承载，勿混用（避免视觉回归） */
  statusSuccess: string
  statusError: string
  /** 状态错误色悬浮/提亮高光档（明亮珊瑚红，比 statusError 更亮更鲜活） */
  statusErrorHover: string
  statusWarning: string
  statusInfo: string
  /** 成功绿（徽章/横幅/边框用，区别于 statusSuccess 的历史蓝） */
  successGreen: string
  /** 警告琥珀橙（审批提示/警示条用，区别于 statusWarning 的历史紫） */
  warningAmber: string
  /** Git diff 新增行底色 */
  diffAddBg: string
  /** Git diff 删除行底色 */
  diffRemoveBg: string
  /** Git diff 新增行前景（+/行号） */
  diffAddFg: string
  /** Git diff 删除行前景（-/行号） */
  diffRemoveFg: string

  /** 状态色弱背景（标签、横幅底色） */
  statusErrorBg: string
  /** 状态色亮暗红弱背景（比 statusErrorBg 稍亮，用于次级高可见度按钮底色） */
  statusErrorBgWeak: string
  /** 状态色深实底（深暗红实底，如次级危险操作、次级审批拒绝底色等，对应以前那档暗红） */
  statusErrorBgSolid: string
  statusSuccessBg: string
  /** 琥珀橙弱底（审批提示悬浮、警示横幅用，配 warningAmber 前景） */
  warningAmberBg: string
  /** 发光/光晕类 alpha 柔和色（box-shadow 场景，比基准色更透） */
  statusErrorSoft: string
  primarySoft: string

  /** 叠加系（overlay）：hover 提亮 / 边框叠加 / 毛玻璃底等场景用的半透明叠加色。
   *  暗色主题为白色叠加（在深底上提亮），浅色主题必须给黑色叠加（白底上白色不可见） */
  overlayVeil: string
  overlayVeilStrong: string
  /** 最高强度叠加：滚动条 hover 等需要明显反馈的场景 */
  overlayVeilMax: string
  overlayBgGlassy: string
  overlayBgGlassyStrong: string

  /** 阴影（卡片、浮层） */
  shadowCard: string
  shadowOverlay: string

  /** 登录页专属：深空径向渐变底（品牌氛围背景，明暗主题均使用深底） */
  loginBgStart: string
  loginBgEnd: string

  /** 登录卡片专属：明暗主题视觉差异大（深色厚重实底毛玻璃 / 浅色轻盈白毛玻璃），
   *  不复用共享 overlay 令牌，避免为迁就登录页调值而牵动聊天输入区等同源引用 */
  loginCardBg: string
  loginCardBorder: string
  loginCardShadow: string
  /** 登录输入框专属底色：深色为半透深底（毛玻璃透感），浅色为纯白实底 */
  loginInputBg: string

  /** 滚动条专属：thumb 常态与悬浮档。深色走白叠加，浅色为 slate 灰，
   *  与共享的 overlayVeil 解耦（浅色下 veil 令牌过淡，白底上不可见） */
  scrollbarThumb: string
  scrollbarThumbHover: string
}

/** 主题名（登记处）：新主题在此扩展 */
export type ThemeName = 'dark' | 'light'

/** 默认暗色主题：色值继承自原 main.css :root 变量，保证重构后视觉零变化 */
const DARK_THEME: ThemeVars = {
  primary: '#81b6e5',
  primaryHover: '#9ec7eb',
  primaryPressed: '#659fcb',
  primaryBgWeak: 'rgba(129, 182, 229, 0.12)',
  primaryBgSolid: '#1d2c3a',
  accent: '#bbbbe9',
  accentBgWeak: 'rgba(187, 187, 233, 0.12)',

  bgBase: '#101014',
  bgCard: '#18181c',
  bgCardActive: '#1d2c3a',
  bgElevated: '#1a1a1f',
  bgModal: '#222227',
  bgInset: '#141418',
  bgCode: '#0b0b0e',

  textBright: '#ffffff',
  // 正文色刻意调暗并去色相：由原 #e4e4eb（冷紫调）改为纯中性灰 #dfdfdf，降低长文阅读刺眼感，
  // 属刻意取舍（不再跟随主题的冷紫色相），勿随意调回
  textBase: '#dfdfdf',
  textMuted: '#a8a8b2',
  textFaint: '#787882',
  textOnPrimary: '#101014',

  borderBase: '#2d2d30',
  borderActive: 'rgba(129, 182, 229, 0.45)',

  statusSuccess: '#63e2b7',
  statusError: '#d03050',
  statusErrorHover: '#ff5c7c',
  statusWarning: '#d03050',
  statusInfo: '#81b6e5',
  successGreen: '#63e2b7',
  warningAmber: '#f0a020',
  diffAddBg: 'rgba(46, 160, 67, 0.18)',
  diffRemoveBg: 'rgba(248, 81, 73, 0.18)',
  diffAddFg: '#3fb950',
  diffRemoveFg: '#f85149',

  statusErrorBg: 'rgba(208, 48, 80, 0.15)',
  statusErrorBgWeak: 'rgba(255, 92, 124, 0.22)',
  statusErrorBgSolid: '#381c24',
  statusSuccessBg: 'rgba(99, 226, 183, 0.12)',
  warningAmberBg: 'rgba(240, 160, 32, 0.12)',
  statusErrorSoft: 'rgba(255, 92, 124, 0.36)',
  primarySoft: 'rgba(129, 182, 229, 0.2)',

  overlayVeil: 'rgba(255, 255, 255, 0.06)',
  overlayVeilStrong: 'rgba(255, 255, 255, 0.12)',
  overlayVeilMax: 'rgba(255, 255, 255, 0.35)',
  overlayBgGlassy: 'rgba(30, 30, 35, 0.4)',
  overlayBgGlassyStrong: 'rgba(40, 40, 45, 0.55)',

  shadowCard: '0 8px 32px rgba(0, 0, 0, 0.25)',
  shadowOverlay: '0 12px 40px rgba(0, 0, 0, 0.45)',

  // 登录卡片（深色）：厚重实底毛玻璃 + 白色叠加亮边 + 深投影
  loginCardBg: 'rgba(20, 20, 25, 0.76)',
  loginCardBorder: 'rgba(255, 255, 255, 0.12)',
  loginCardShadow: '0 20px 50px rgba(0, 0, 0, 0.65)',
  loginInputBg: 'rgba(16, 16, 20, 0.6)',

  // 滚动条（深色）：白叠加档位，深底上低调不抢视觉；与悬浮档成对取值
  scrollbarThumb: 'rgba(255, 255, 255, 0.12)',
  scrollbarThumbHover: 'rgba(255, 255, 255, 0.35)',

  loginBgStart: '#1b2838',
  loginBgEnd: '#0d131a'
}

/** 浅色白色主题：小清新、轻盈通透、高质感冷白雪青底与微透浅紫胶囊 */
const LIGHT_THEME: ThemeVars = {
  primary: '#2575c0',
  primaryHover: '#398ad6',
  primaryPressed: '#1d62a4',
  primaryBgWeak: 'rgba(37, 117, 192, 0.08)',
  primaryBgSolid: '#e8f3fc',
  accent: '#6355c7',
  accentBgWeak: 'rgba(99, 85, 199, 0.06)',

  bgBase: '#ffffff',
  bgCard: '#ffffff',
  bgCardActive: '#e8f3fc',
  bgElevated: '#ffffff',
  bgModal: '#ffffff',
  bgInset: '#f8fafc',
  bgCode: '#f8fafc',

  // 文字四档统一中性灰阶：纯黑 → #1b1b1b → #595959 → #8c8c8c，逐档抬亮（去 slate 蓝调）
  textBright: '#000000',
  textBase: '#1b1b1b',
  textMuted: '#595959',
  textFaint: '#8c8c8c',
  textOnPrimary: '#ffffff',

  borderBase: '#e2e8f0',
  borderActive: 'rgba(37, 117, 192, 0.35)',

  statusSuccess: '#209a54',
  statusError: '#c9354d',
  statusErrorHover: '#ea3a5c',
  statusWarning: '#c9354d',
  statusInfo: '#2575c0',
  successGreen: '#209a54',
  warningAmber: '#d98a00',
  diffAddBg: 'rgba(46, 160, 67, 0.12)',
  diffRemoveBg: 'rgba(248, 81, 73, 0.10)',
  diffAddFg: '#237834',
  diffRemoveFg: '#c23838',

  statusErrorBg: 'rgba(201, 53, 77, 0.06)',
  statusErrorBgWeak: 'rgba(234, 58, 92, 0.10)',
  statusErrorBgSolid: '#fbebed',
  statusSuccessBg: 'rgba(32, 154, 84, 0.08)',
  warningAmberBg: 'rgba(217, 138, 0, 0.08)',
  statusErrorSoft: 'rgba(234, 58, 92, 0.20)',
  primarySoft: 'rgba(37, 117, 192, 0.16)',

  overlayVeil: 'rgba(15, 23, 42, 0.02)',
  overlayVeilStrong: 'rgba(15, 23, 42, 0.05)',
  overlayVeilMax: 'rgba(15, 23, 42, 0.12)',
  overlayBgGlassy: 'rgba(255, 255, 255, 0.96)',
  overlayBgGlassyStrong: 'rgba(255, 255, 255, 0.98)',

  shadowCard: '0 2px 10px -2px rgba(15, 23, 42, 0.06), 0 1px 3px rgba(15, 23, 42, 0.04)',
  shadowOverlay: '0 12px 36px -4px rgba(15, 23, 42, 0.08), 0 4px 12px -2px rgba(15, 23, 42, 0.04)',

  // 登录卡片（浅色）：轻盈白毛玻璃，与深色的厚重实底形成鲜明主题差异（故不复用共享 overlay 令牌）
  loginCardBg: 'rgba(255, 255, 255, 0.98)',
  loginCardBorder: '#e2e8f0',
  loginCardShadow: '0 12px 36px -4px rgba(15, 23, 42, 0.08), 0 4px 12px -2px rgba(15, 23, 42, 0.04)',
  loginInputBg: '#ffffff',

  // 滚动条（浅色）：中性灰 #a6a6a6 与全站文字灰阶同调，白底合成约 #dbdbdb，轻盈克制；悬浮提亮 0.20
  scrollbarThumb: 'rgba(166, 166, 166, 0.40)',
  scrollbarThumbHover: 'rgba(166, 166, 166, 0.60)',

  loginBgStart: '#ffffff',
  loginBgEnd: '#f8fafd'
}

/** 全部主题注册表 */
export const THEMES: Record<ThemeName, ThemeVars> = {
  dark: DARK_THEME,
  light: LIGHT_THEME
}

/** 变量名 → CSS 自定义属性名的映射（applyTheme 的写入口径，与 main.css 引用严格一致） */
const CSS_VAR_MAP: Array<{ cssVar: string; tokenKey: keyof ThemeVars }> = [
  { cssVar: '--primary-color', tokenKey: 'primary' },
  { cssVar: '--primary-color-hover', tokenKey: 'primaryHover' },
  { cssVar: '--primary-color-pressed', tokenKey: 'primaryPressed' },
  { cssVar: '--primary-bg-weak', tokenKey: 'primaryBgWeak' },
  { cssVar: '--primary-bg-solid', tokenKey: 'primaryBgSolid' },
  { cssVar: '--accent-color', tokenKey: 'accent' },
  { cssVar: '--accent-bg-weak', tokenKey: 'accentBgWeak' },
  { cssVar: '--bg-color', tokenKey: 'bgBase' },
  { cssVar: '--bg-color-card', tokenKey: 'bgCard' },
  { cssVar: '--bg-color-card-active', tokenKey: 'bgCardActive' },
  { cssVar: '--bg-color-elevated', tokenKey: 'bgElevated' },
  { cssVar: '--bg-color-modal', tokenKey: 'bgModal' },
  { cssVar: '--bg-color-inset', tokenKey: 'bgInset' },
  { cssVar: '--bg-color-code', tokenKey: 'bgCode' },
  { cssVar: '--text-color-bright', tokenKey: 'textBright' },
  { cssVar: '--text-color', tokenKey: 'textBase' },
  { cssVar: '--text-color-muted', tokenKey: 'textMuted' },
  { cssVar: '--text-color-faint', tokenKey: 'textFaint' },
  { cssVar: '--text-color-dark', tokenKey: 'textOnPrimary' },
  { cssVar: '--border-color', tokenKey: 'borderBase' },
  { cssVar: '--border-color-active', tokenKey: 'borderActive' },
  { cssVar: '--status-success', tokenKey: 'statusSuccess' },
  { cssVar: '--status-error', tokenKey: 'statusError' },
  { cssVar: '--status-error-hover', tokenKey: 'statusErrorHover' },
  { cssVar: '--status-warning', tokenKey: 'statusWarning' },
  { cssVar: '--status-info', tokenKey: 'statusInfo' },
  { cssVar: '--success-green', tokenKey: 'successGreen' },
  { cssVar: '--warning-amber', tokenKey: 'warningAmber' },
  { cssVar: '--diff-add-bg', tokenKey: 'diffAddBg' },
  { cssVar: '--diff-remove-bg', tokenKey: 'diffRemoveBg' },
  { cssVar: '--diff-add-fg', tokenKey: 'diffAddFg' },
  { cssVar: '--diff-remove-fg', tokenKey: 'diffRemoveFg' },
  { cssVar: '--status-error-bg', tokenKey: 'statusErrorBg' },
  { cssVar: '--status-error-bg-weak', tokenKey: 'statusErrorBgWeak' },
  { cssVar: '--status-error-bg-solid', tokenKey: 'statusErrorBgSolid' },
  { cssVar: '--status-success-bg', tokenKey: 'statusSuccessBg' },
  { cssVar: '--warning-amber-bg', tokenKey: 'warningAmberBg' },
  { cssVar: '--status-error-soft', tokenKey: 'statusErrorSoft' },
  { cssVar: '--primary-soft', tokenKey: 'primarySoft' },
  { cssVar: '--overlay-veil', tokenKey: 'overlayVeil' },
  { cssVar: '--overlay-veil-strong', tokenKey: 'overlayVeilStrong' },
  { cssVar: '--overlay-veil-max', tokenKey: 'overlayVeilMax' },
  { cssVar: '--overlay-bg-glassy', tokenKey: 'overlayBgGlassy' },
  { cssVar: '--overlay-bg-glassy-strong', tokenKey: 'overlayBgGlassyStrong' },
  { cssVar: '--shadow-card', tokenKey: 'shadowCard' },
  { cssVar: '--shadow-overlay', tokenKey: 'shadowOverlay' },
  { cssVar: '--login-bg-start', tokenKey: 'loginBgStart' },
  { cssVar: '--login-bg-end', tokenKey: 'loginBgEnd' },
  { cssVar: '--login-card-bg', tokenKey: 'loginCardBg' },
  { cssVar: '--login-card-border', tokenKey: 'loginCardBorder' },
  { cssVar: '--login-card-shadow', tokenKey: 'loginCardShadow' },
  { cssVar: '--login-input-bg', tokenKey: 'loginInputBg' },
  { cssVar: '--scrollbar-thumb', tokenKey: 'scrollbarThumb' },
  { cssVar: '--scrollbar-thumb-hover', tokenKey: 'scrollbarThumbHover' }
]

/**
 * 应用主题：把令牌写入文档根元素的 CSS 自定义属性。
 * main.ts 启动时调用一次（默认 dark），后续切主题时再次调用即可全站生效。
 */
export function applyTheme(name: ThemeName): void {
  const tokens = THEMES[name] ?? THEMES.dark
  const root = document.documentElement
  for (const { cssVar, tokenKey } of CSS_VAR_MAP) {
    root.style.setProperty(cssVar, tokens[tokenKey])
  }
  // data-theme 标记当前主题，供 CSS 选择器区分（如浅色下的特殊微调）与测试定位
  root.setAttribute('data-theme', name)
}

/**
 * 从主题变量派生 Naive UI 的 GlobalThemeOverrides。
 * <p>
 * 保证组件库配色与自绘 CSS 永远同源：两套样式体系引用同一份主题变量，
 * 切换主题时只需 applyTheme() + 重建本对象，不存在两处调色各自漂移的问题。
 * </p>
 */
export function buildNaiveThemeOverrides(vars: ThemeVars): Record<string, any> {
  return {
    common: {
      primaryColor: vars.primary,
      primaryColorHover: vars.primaryHover,
      primaryColorPressed: vars.primaryPressed,
      primaryColorSuppl: vars.primaryHover,
      bodyColor: vars.bgBase,
      cardColor: vars.bgCard,
      popoverColor: vars.bgElevated,
      modalColor: vars.bgModal,
      textColorBase: vars.textBright,
      textColor1: vars.textBright,
      textColor2: vars.textBase,
      textColor3: vars.textMuted,
      borderColor: vars.borderBase,
      dividerColor: vars.borderBase,
      successColor: vars.statusSuccess,
      errorColor: vars.statusError,
      warningColor: vars.statusWarning,
      infoColor: vars.statusInfo,
      borderRadius: '6px'
    },
    Input: {
      borderFocus: `1px solid ${vars.primary}`,
      borderHover: `1px solid ${vars.primaryHover}`,
      boxShadowFocus: `0 0 8px ${vars.borderActive}`
    },
    Select: {
      peers: {
        InternalSelection: {
          borderFocus: `1px solid ${vars.primary}`,
          borderHover: `1px solid ${vars.primaryHover}`,
          boxShadowFocus: `0 0 8px ${vars.borderActive}`
        }
      }
    },
    Button: {
      textColorError: '#ffffff',
      textColorHoverError: '#ffffff',
      textColorPressedError: '#ffffff',
      textColorFocusError: '#ffffff'
    },
    Modal: {
      color: vars.bgModal,
      textColor: vars.textBase,
      boxShadow: vars.shadowOverlay
    },
    Card: {
      colorModal: vars.bgModal,
      colorPopover: vars.bgElevated,
      borderColor: vars.borderBase,
      titleTextColor: vars.textBright,
      textColor: vars.textBase
    },
    Drawer: {
      color: vars.bgModal,
      textColor: vars.textBase,
      titleTextColor: vars.textBright,
      boxShadow: vars.shadowOverlay,
      headerBorderBottom: `1px solid ${vars.borderBase}`,
      footerBorderTop: `1px solid ${vars.borderBase}`
    },
    Dialog: {
      color: vars.bgModal,
      border: `1px solid ${vars.borderBase}`,
      titleTextColor: vars.textBright,
      textColor: vars.textBase,
      boxShadow: vars.shadowOverlay
    },
    Popover: {
      color: vars.bgElevated,
      textColor: vars.textBase,
      borderColor: vars.borderBase
    }
  }
}
