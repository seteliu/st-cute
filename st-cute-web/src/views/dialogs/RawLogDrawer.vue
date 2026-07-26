<template>
  <n-drawer v-model:show="appStore.showLogDrawer" :width="width" placement="right">
    <n-drawer-content :title="t('logDrawer.title')" closable style="background-color: #18181c; color: #fff;">
      <div style="display: flex; flex-direction: column; gap: 12px; height: 100%;">
        
        <!-- 工具调用基本信息及完整参数展示 -->
        <div 
          v-if="appStore.currentViewToolCall" 
          style="background-color: #101014; border: 1px solid #2d2d30; padding: 12px; border-radius: 6px; font-family: monospace;"
        >
          <div style="margin-bottom: 6px; font-weight: bold; color: var(--primary-color); font-size: 0.95rem;">
            {{ t('chat.toolNameLabel') }}: {{ formatToolName(appStore.currentViewToolCall.name) }}
          </div>
          <div style="color: var(--status-warning); font-size: 0.85rem; font-weight: 600;">
            {{ t('chat.argumentsLabel') }} (Arguments):
          </div>
          <pre style="margin: 6px 0 0 0; background-color: #141418; padding: 8px; border-radius: 4px; color: #a0a0a5; font-size: 0.8rem; overflow-x: auto; overflow-y: auto; max-height: 180px; white-space: pre-wrap; word-break: break-all;"
            >{{ formattedArguments }}</pre>
        </div>

        <!-- 执行结果展示 -->
        <div style="flex: 1; display: flex; flex-direction: column; min-height: 0; gap: 12px;">
          <!-- 没截断时：直接显示全部内容 -->
          <template v-if="!appStore.currentViewMessage || !appStore.currentViewMessage.beforeCompactContent">
            <div style="font-weight: bold; color: var(--primary-color); font-size: 0.95rem; font-family: monospace;">
              {{ t('common.tip') }} (Response Output):
            </div>
            <pre
              style="background: #101014; color: #c2c2c9; padding: 15px; border-radius: 6px; overflow: auto; font-family: monospace; font-size: 0.85rem; flex: 1; margin: 0; border: 1px solid #2d2d30; white-space: pre-wrap; word-break: break-all;"
              >{{ appStore.currentViewMessage ? appStore.currentViewMessage.content : appStore.rawLogContent }}</pre>
          </template>

          <!-- 截断时：多显示一个截断前的完整日志 -->
          <template v-else>
            <!-- 1. 精简摘要 (content) -->
            <div style="display: flex; flex-direction: column; gap: 4px; max-height: 200px; flex-shrink: 0; min-height: 0;">
              <div style="font-weight: bold; color: var(--status-warning); font-size: 0.95rem; font-family: monospace;">
                Compact Summary:
              </div>
              <pre
                style="background: #101014; color: var(--status-warning); padding: 10px; border-radius: 6px; overflow: auto; font-family: monospace; font-size: 0.8rem; margin: 0; border: 1px dashed var(--status-warning); white-space: pre-wrap; word-break: break-all; flex: 1;"
                >{{ appStore.currentViewMessage.content }}</pre>
            </div>

            <!-- 2. 截断前的完整大日志 (beforeCompactContent) -->
            <div style="flex: 1; display: flex; flex-direction: column; min-height: 0;">
              <div style="font-weight: bold; color: var(--primary-color); font-size: 0.95rem; margin-bottom: 6px; font-family: monospace;">
                Original Raw Output:
              </div>
              <pre
                style="background: #101014; color: #c2c2c9; padding: 15px; border-radius: 6px; overflow: auto; font-family: monospace; font-size: 0.85rem; flex: 1; margin: 0; border: 1px solid #2d2d30; white-space: pre-wrap; word-break: break-all;"
                >{{ appStore.currentViewMessage.beforeCompactContent }}</pre>
            </div>
          </template>
        </div>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/stores/app'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'

const { isMobile } = useResponsive()

const width = computed(() => {
  return isMobile.value ? '100%' : 650
})

const appStore = useAppStore()

const formatToolName = (name: string) => {
  if (name.endsWith('Tool')) {
    return name.slice(0, -4)
  }
  return name
}

const formattedArguments = computed(() => {
  const tc = appStore.currentViewToolCall
  if (!tc) return ''
  const args = tc.args || tc.arguments
  if (!args) return '{}'
  if (typeof args === 'object') {
    return JSON.stringify(args, null, 2)
  }
  try {
    const parsed = JSON.parse(args)
    return JSON.stringify(parsed, null, 2)
  } catch (e) {
    return args
  }
})
</script>

<style scoped>
</style>
