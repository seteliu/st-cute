import { computed, ref } from 'vue'
import { THEMES, ThemeName, applyTheme, buildNaiveThemeOverrides } from './themeVars'

export const THEME_STORAGE_KEY = 'st-cute-theme'

/**
 * 从本地持久化快照读取主题，默认为 dark。
 * <p>
 * 关键防闪机制：在后端配置接口异步返回之前，同步从本地读取上次的主题设定，
 * 保证首屏渲染在第 0 毫秒即可直接匹配正确主题，彻底消灭“先黑后白”的闪烁感。
 * </p>
 */
export function getStoredTheme(): ThemeName {
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY)
    if (saved === 'light' || saved === 'dark') {
      return saved
    }
  } catch (e) {
    // 无痕模式或特定存储受限环境下安全降级
  }
  return 'dark'
}

/**
 * 当前主题响应式状态（全局单例，默认优先取本地快照）。
 */
export const currentTheme = ref<ThemeName>(getStoredTheme())

/** Naive UI 明暗模式的主题名（dark 之外的都按 light 处理） */
export const naiveThemeMode = computed<ThemeName>(() => (currentTheme.value === 'dark' ? 'dark' : 'light'))

/** 当前主题的主题变量快照（派生 Naive UI 覆盖用） */
export const activeThemeVars = computed(() => THEMES[currentTheme.value])

/** 当前主题派生的 Naive UI 覆盖（App.vue 计算属性直接引用） */
export const naiveThemeOverrides = computed(() => buildNaiveThemeOverrides(activeThemeVars.value))

/**
 * 切换主题：写令牌到 CSS 变量、更新响应式状态并同步持久化到本地快照。
 * 多主题设置项上线时由设置页或配置同步监听调用。
 */
export function switchTheme(name: ThemeName): void {
  applyTheme(name)
  currentTheme.value = name
  try {
    localStorage.setItem(THEME_STORAGE_KEY, name)
  } catch (e) {
    // 忽略异常
  }
}
