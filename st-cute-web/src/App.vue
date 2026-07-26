<template>
  <n-config-provider :theme="darkTheme" :theme-overrides="themeOverrides" :locale="naiveLocale" :date-locale="naiveDateLocale">
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
import { darkTheme, GlobalThemeOverrides, useMessage, useDialog, zhCN, dateZhCN, enUS, dateEnUS } from 'naive-ui'
import { currentLang } from '@/i18n'

const naiveLocale = computed(() => (currentLang.value === 'en-US' ? enUS : zhCN))
const naiveDateLocale = computed(() => (currentLang.value === 'en-US' ? dateEnUS : dateZhCN))

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

// Naive UI 自定义全局配色
const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#81b6e5',
    primaryColorHover: '#9ec7eb',
    primaryColorPressed: '#659fcb',
    primaryColorSuppl: '#9ec7eb',
    bodyColor: '#101014',
    cardColor: '#18181c'
  },
  Input: {
    borderFocus: '1px solid #81b6e5',
    borderHover: '1px solid #9ec7eb',
    boxShadowFocus: '0 0 8px rgba(129, 182, 229, 0.25)'
  },
  Select: {
    peers: {
      InternalSelection: {
        borderFocus: '1px solid #81b6e5',
        borderHover: '1px solid #9ec7eb',
        boxShadowFocus: '0 0 8px rgba(129, 182, 229, 0.25)'
      }
    }
  }
}
</script>

<style>
/* CSS 全局样式均已抽取至 assets/styles/main.css 中，其余采用 UI 框架内置属性 */
</style>
