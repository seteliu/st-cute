<template>
  <n-config-provider :theme="naiveTheme" :theme-overrides="themeOverrides" :locale="naiveLocale" :date-locale="naiveDateLocale">
    <n-message-provider>
      <message-provider-content />
      <n-dialog-provider>
        <dialog-provider-content />
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { defineComponent, computed } from 'vue'
import { darkTheme, lightTheme, GlobalThemeOverrides, useMessage, useDialog, zhCN, dateZhCN, enUS, dateEnUS } from 'naive-ui'
import { currentLang } from '@/i18n'
import { currentTheme, naiveThemeMode, naiveThemeOverrides } from '@/styles/theme'

const naiveLocale = computed(() => (currentLang.value === 'en-US' ? enUS : zhCN))
const naiveDateLocale = computed(() => (currentLang.value === 'en-US' ? dateEnUS : dateZhCN))

// Naive UI 明暗主题对象随主题状态联动（暗色用内置 darkTheme，浅色用 lightTheme）
const naiveTheme = computed(() => (naiveThemeMode.value === 'light' ? lightTheme : darkTheme))
const themeOverrides = computed<GlobalThemeOverrides>(() => naiveThemeOverrides.value)

const MessageProviderContent = defineComponent({
  setup() {
    (window as any).$message = useMessage()
    return () => null
  }
})

const DialogProviderContent = defineComponent({
  setup() {
    (window as any).$dialog = useDialog()
    return () => null
  }
})
</script>

<style>
/* CSS 全局样式均已抽取至 assets/styles/main.css 中，其余采用 UI 框架内置属性 */
</style>
