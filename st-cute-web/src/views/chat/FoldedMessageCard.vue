<template>
  <div class="folded-message-card">
    <div class="folded-content">
      <div class="info-side">
        <!-- 精致的折叠状态小图标 -->
        <span class="folded-icon">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 14h6v6H4zM14 4h6v6h-6zM4 4h6v6H4zM14 14h6v6h-6z"/>
          </svg>
        </span>
        <span class="folded-text">
          {{ t('chat.foldedSummary', { assistant: assistantCount, tool: toolCount }) }}
        </span>

        <!-- 折叠内容异常黄色感叹号警告图标与悬浮 Tooltip -->
        <n-tooltip v-if="hasError" trigger="hover">
          <template #trigger>
            <span class="folded-warning-badge">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="folded-warning-icon">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
                <line x1="12" y1="9" x2="12" y2="13"></line>
                <line x1="12" y1="17" x2="12.01" y2="17"></line>
              </svg>
            </span>
          </template>
          <div class="folded-warning-tooltip">
            <div class="warning-title">{{ t('chat.foldedWarningTitle') }}</div>
            <div v-for="(err, idx) in errorDetails" :key="idx" class="warning-item">
              • {{ err }}
            </div>
          </div>
        </n-tooltip>
      </div>
      <n-button class="detail-btn" size="tiny" quaternary type="primary" @click="openDrawer">
        {{ t('chat.detail') }}
      </n-button>
    </div>

    <!-- 弹窗式详情容器 -->
    <n-modal
      v-model:show="showDetail"
      :style="modalStyle"
    >
      <n-card
        class="folded-detail-card"
        :style="cardStyle"
        :content-style="cardContentStyle"
        :header-style="cardHeaderStyle"
        closable
        @close="showDetail = false"
      >
        <template #header>
          <div class="detail-header">
            <span class="title-text">{{ t('chat.foldedDetailTitle') }}</span>
            <span class="subtitle-text">{{ t('chat.foldedDetailSubtitle', { assistant: assistantCount, tool: toolCount }) }}</span>
          </div>
        </template>
        
        <div class="detail-body" :style="contentStyle">
          <div class="detail-chat-flow">
            <message-list-flow
              :messages="foldedItems"
              :is-sub-agent="true"
              :cid="cid"
            />
          </div>
        </div>
      </n-card>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, defineAsyncComponent, type CSSProperties } from 'vue'
import { useResponsive } from '@/utils/useResponsive'
import { RenderItem } from './MessageListFlow.vue'
import { t } from '@/i18n'
import { formatToolName } from '@/utils/toolName'

const props = defineProps<{
  foldedItems: RenderItem[]
  cid?: number | null
}>()

// 使用异步组件导入，彻底避免 Vue 组件循环引用加载死锁 (MessageListFlow <-> FoldedMessageCard)
const MessageListFlow = defineAsyncComponent(() => import('./MessageListFlow.vue'))
const { isMobile } = useResponsive()
const showDetail = ref(false)

const openDrawer = () => {
  showDetail.value = true
}

// 统计助手消息数
const assistantCount = computed(() => {
  return props.foldedItems.filter(
    item => item.type === 'message' &&
    (item.data.role === 'assistant' || item.data.role === 'branch' || item.data.role === 'compressed')
  ).length
})

// 统计工具消息数
const toolCount = computed(() => {
  let count = 0
  for (const item of props.foldedItems) {
    if (item.type === 'message' && item.tools) {
      count += item.tools.length
    } else if (item.type === 'tool_group' && item.tools) {
      count += item.tools.length
    }
  }
  return count
})

// 扫描折叠项中的异常与失败操作
const errorDetails = computed<string[]>(() => {
  const errors: string[] = []
  for (const item of props.foldedItems) {
    if (item.type === 'message') {
      if (item.data.status === 'FAILED') {
        errors.push(t('chat.foldedFailedMsg'))
      }
      if (item.tools && item.tools.length > 0) {
        for (const tool of item.tools) {
          if (tool.status === 'FAILED') {
            const name = tool.toolName || (tool as any).name || 'tool'
            errors.push(t('chat.foldedFailedTool', { name: formatToolName(name) }))
          }
        }
      }
    } else if (item.type === 'tool_group' && item.tools) {
      for (const tool of item.tools) {
        if (tool.status === 'FAILED') {
          const name = tool.toolName || (tool as any).name || 'tool'
          errors.push(t('chat.foldedFailedTool', { name: formatToolName(name) }))
        }
      }
    }
  }
  return errors
})

const hasError = computed(() => errorDetails.value.length > 0)

// 弹窗与样式自适应控制 (自适应适配 PC 和移动端)
const modalStyle = computed<CSSProperties>(() => {
  if (isMobile.value) {
    // 移动端全屏弹层：使用 svh（小视口单位）按地址栏展开时的最小可视区域取高，避免地址栏弹出时底部截断
    return {
      width: '100vw !important',
      height: '100svh !important',
      maxHeight: '100svh !important',
      margin: '0 !important',
      borderRadius: '0 !important'
    }
  } else {
    return {
      width: '75vw !important',
      maxWidth: '1200px'
    }
  }
})

const cardStyle = computed<CSSProperties>(() => {
  if (isMobile.value) {
    // 同上：svh 小视口单位，保证移动端地址栏展开时卡片完整可见
    return {
      height: '100svh',
      display: 'flex',
      flexDirection: 'column',
      borderRadius: '0'
    }
  }
  return {}
})

const cardContentStyle = computed<CSSProperties>(() => {
  return {
    padding: isMobile.value ? '0 0 12px 0' : '24px',
    display: 'flex',
    flexDirection: 'column',
    flex: 1,
    minHeight: 0
  }
})

const cardHeaderStyle = computed<CSSProperties | undefined>(() => {
  return isMobile.value
    ? { padding: '12px 16px 8px 16px', borderBottom: '1px solid var(--border-color)' }
    : { borderBottom: '1px solid var(--border-color)', paddingBottom: '16px' }
})

const contentStyle = computed<CSSProperties>(() => {
  if (isMobile.value) {
    return {
      display: 'flex',
      flexDirection: 'column',
      flex: 1,
      minHeight: 0,
      width: '100%',
      boxSizing: 'border-box',
      paddingTop: '0'
    }
  } else {
    return {
      display: 'flex',
      flexDirection: 'column',
      height: '70vh',
      width: '100%',
      boxSizing: 'border-box',
      paddingTop: '8px'
    }
  }
})
</script>

<style scoped>
.folded-message-card {
  width: fit-content;
  max-width: 85%;
  margin: 6px 0;
  box-sizing: border-box;
}

.folded-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: rgba(255, 255, 255, 0.02);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 8px 16px;
  box-sizing: border-box;
  transition: border-color 0.25s, background-color 0.25s;
  gap: 16px;
}

.folded-content:hover {
  background-color: rgba(255, 255, 255, 0.035);
  border-color: var(--primary-color-hover);
}

.info-side {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.85rem;
  color: var(--text-color-muted);
}

.folded-icon {
  display: flex;
  align-items: center;
  color: var(--primary-color);
  opacity: 0.85;
}

.folded-icon svg {
  width: 14px;
  height: 14px;
}

.folded-text {
  user-select: none;
}

.folded-warning-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--status-warning, #f0a020);
  margin-left: 2px;
  animation: pulse-warning 2.5s infinite ease-in-out;
}

.folded-warning-icon {
  width: 15px;
  height: 15px;
}

.folded-warning-tooltip {
  max-width: 280px;
  font-size: 0.82rem;
  line-height: 1.4;
}

.folded-warning-tooltip .warning-title {
  font-weight: bold;
  color: var(--status-warning, #f0a020);
  margin-bottom: 4px;
}

.folded-warning-tooltip .warning-item {
  color: var(--text-color, #e0e0e0);
  margin-top: 2px;
}

@keyframes pulse-warning {
  0%, 100% {
    opacity: 0.9;
    transform: scale(1);
  }
  50% {
    opacity: 1;
    transform: scale(1.1);
  }
}

.detail-btn {
  font-weight: 500;
  padding: 0 8px;
}

/* 弹窗样式 */
.folded-detail-card {
  width: 100% !important;
  max-width: 100% !important;
  background-color: #18181c !important;
  color: #fff !important;
  display: flex;
  flex-direction: column;
}

.detail-header {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.title-text {
  font-size: 1rem;
  font-weight: bold;
  color: var(--text-color-bright);
}

.subtitle-text {
  font-size: 0.75rem;
  font-weight: normal;
  color: var(--text-color-muted);
}

.detail-body {
  overflow: hidden;
}

.detail-chat-flow {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
}

/* 确保详情里的 virtual list 被限制在容器内滚动 */
:deep(.message-flow-list) {
  height: 100%;
  overflow-y: auto;
}
</style>
