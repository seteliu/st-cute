import { computed, ref } from 'vue'
import { THEMES, ThemeName, applyTheme, buildNaiveThemeOverrides } from './themeVars'

/**
 * 当前主题响应式状态（全局单例）。
 * <p>
 * 多主题功能的唯一入口：当前仅 dark 默认主题，未来接入设置项时
 * 在此扩展持久化（localStorage / 后端配置）与切换函数即可，视图层只读引用。
 * </p>
 */
export const currentTheme = ref<ThemeName>('dark')

/** Naive UI 明暗模式的主题名（dark 之外的都按 light 处理） */
export const naiveThemeMode = computed<ThemeName>(() => (currentTheme.value === 'dark' ? 'dark' : 'light'))

/** 当前主题的主题变量快照（派生 Naive UI 覆盖用） */
export const activeThemeVars = computed(() => THEMES[currentTheme.value])

/** 当前主题派生的 Naive UI 覆盖（App.vue 计算属性直接引用） */
export const naiveThemeOverrides = computed(() => buildNaiveThemeOverrides(activeThemeVars.value))

/**
 * 切换主题：写令牌到 CSS 变量并更新响应式状态（Naive UI 配色经 computed 自动跟随）。
 * 多主题设置项上线时由设置页调用。
 */
export function switchTheme(name: ThemeName): void {
  applyTheme(name)
  currentTheme.value = name
}
