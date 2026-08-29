<template>
  <n-drawer v-model:show="appStore.showThoughtDrawer" :width="width" placement="right">
    <n-drawer-content :title="t('chat.thoughtDetailTitle')" closable style="background-color: #18181c; color: #fff;">
      <div class="thought-drawer-container">
        <!-- 头部操作与信息栏 -->
        <div class="thought-drawer-header">
          <div class="thought-stats">
            <span>{{ charCountText }}</span>
          </div>
          <n-button
            size="tiny"
            quaternary
            type="primary"
            @click="handleCopy"
          >
            <template #icon>
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
              </svg>
            </template>
            {{ t('chat.copyContent') }}
          </n-button>
        </div>

        <!-- 思考内容全文展示 -->
        <div class="thought-drawer-body">
          <pre ref="contentBoxRef" class="thought-full-content">{{ currentThoughtText }}</pre>
        </div>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useAgentStore } from '@/stores/agent'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'

const { isMobile } = useResponsive()
const appStore = useAppStore()
const conversationStore = useConversationStore()
const agentStore = useAgentStore()

const contentBoxRef = ref<HTMLElement | null>(null)

const width = computed(() => {
  return isMobile.value ? '100%' : 650
})

// 响应式解析当前查看的思考内容（支持主会话与子代理流式实时同步）
const currentThoughtText = computed(() => {
  const targetId = appStore.currentViewThoughtMessageId
  if (targetId) {
    // 1. 先在主会话消息列表中查找
    const mainMsg = conversationStore.messages.find(m => m.id === targetId)
    if (mainMsg && mainMsg.thought !== undefined) {
      return mainMsg.thought
    }
    // 2. 在当前激活的子代理消息列表中查找
    const subAgent = agentStore.activeSubAgent
    if (subAgent && subAgent.messages) {
      const subMsg = subAgent.messages.find((m: any) => m.id === targetId)
      if (subMsg && subMsg.thought !== undefined) {
        return subMsg.thought
      }
    }
  }
  // 3. 兜底返回快照内容
  return appStore.thoughtDetailContent || ''
})

const charCountText = computed(() => {
  const len = currentThoughtText.value ? currentThoughtText.value.length : 0
  return `共 ${len} 字符`
})

// 打开抽屉时初始化置底
watch(
  () => appStore.showThoughtDrawer,
  (show) => {
    if (show) {
      nextTick(() => {
        if (contentBoxRef.value) {
          contentBoxRef.value.scrollTop = contentBoxRef.value.scrollHeight
        }
      })
    }
  }
)

// 流式接收时自动跟随置底（若用户向上阅读则不强行打扰）
watch(
  () => currentThoughtText.value,
  () => {
    if (appStore.showThoughtDrawer) {
      nextTick(() => {
        if (contentBoxRef.value) {
          const el = contentBoxRef.value
          const isAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= 60
          if (isAtBottom) {
            el.scrollTop = el.scrollHeight
          }
        }
      })
    }
  }
)

const handleCopy = () => {
  const text = currentThoughtText.value || ''
  navigator.clipboard.writeText(text).then(() => {
    if ((window as any).$message) {
      (window as any).$message.success(t('chat.copiedSuccess'))
    }
  }).catch((e) => {
    console.error('复制思考内容失败:', e)
  })
}
</script>

<style scoped>
.thought-drawer-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 12px;
}

.thought-drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
  border-bottom: 1px solid #2d2d30;
}

.thought-stats {
  font-size: 0.8rem;
  color: #a0a0a5;
  font-family: monospace;
}

.thought-drawer-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.thought-full-content {
  background: #101014;
  color: #c2c2c9;
  padding: 16px;
  border-radius: 6px;
  overflow: auto;
  font-family: monospace;
  font-size: 0.85rem;
  line-height: 1.6;
  flex: 1;
  margin: 0;
  border: 1px solid #2d2d30;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
