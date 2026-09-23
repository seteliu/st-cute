<template>
  <n-modal
    v-model:show="agentStore.showSubAgentDrawer"
    :style="modalStyle"
    @after-enter="() => scrollToBottom(false)"
  >
    <n-card
      v-if="agentStore.activeSubAgent"
      class="subagent-modal-card"
      :style="cardStyle"
      :content-style="cardContentStyle"
      :header-style="cardHeaderStyle"
      closable
      @close="agentStore.showSubAgentDrawer = false"
    >
      <template #header>
        <div style="display: flex; flex-direction: column; width: 100%;">
          <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
            <span style="font-weight: bold;">
              {{ isMobile ? '' : '子代理: ' }}{{ agentStore.activeSubAgent.role }} (ID: {{ agentStore.activeSubAgent.cid }})
            </span>
            <sub-agent-status-tag :status="agentStore.activeSubAgent.status" />
          </div>
          <!-- 移动端额外操作栏，折行展示 -->
          <div v-if="isMobile" style="display: flex; align-items: center; gap: 12px; margin-top: 6px; font-size: 0.8rem; color: var(--text-color-muted); font-weight: 500;">
            <token-metrics-tooltip
              :input-tokens="agentStore.activeSubAgent.inputTokens || 0"
              :output-tokens="agentStore.activeSubAgent.outputTokens || 0"
              :cached-tokens="agentStore.activeSubAgent.cachedTokens"
              title="子代理上下文详情"
              trigger="click"
              :cid="agentStore.activeSubAgent?.cid ? Number(agentStore.activeSubAgent.cid) : undefined"
              :context-limit="contextLimit"
            >
              <template #default="{ total }">
                上下文窗口: <strong style="color: var(--text-color-bright); font-weight: bold;">{{ formatTokenCount(total) }}</strong><template v-if="contextLimitText"> / {{ contextLimitText }} ({{ usagePercentage(total) }}%)</template>
              </template>
            </token-metrics-tooltip>

            <n-button
              v-if="agentStore.activeSubAgent.status === 'running'"
              size="tiny"
              type="primary"
              @click="agentStore.handleKillMember"
            >
              <template #icon>
                <svg class="spin-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="12" y1="2" x2="12" y2="6"></line>
                  <line x1="12" y1="18" x2="12" y2="22"></line>
                  <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"></line>
                  <line x1="16.24" y1="16.24" x2="19.07" y2="19.07"></line>
                  <line x1="2" y1="12" x2="6" y2="12"></line>
                  <line x1="18" y1="12" x2="22" y2="12"></line>
                  <line x1="4.93" y1="19.07" x2="7.76" y2="16.24"></line>
                  <line x1="16.24" y1="7.76" x2="19.07" y2="4.93"></line>
                </svg>
              </template>
              {{ t('chat.cancelLoop') }}
            </n-button>
          </div>
        </div>
      </template>
      <template #header-extra v-if="!isMobile">
        <div style="display: flex; align-items: center; gap: 12px; padding-right: 12px; font-size: 0.85rem; color: var(--text-color-muted); font-weight: 500;">
          <n-button
            v-if="agentStore.activeSubAgent.status === 'running'"
            size="tiny"
            type="primary"
            @click="agentStore.handleKillMember"
          >
            <template #icon>
              <svg class="spin-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="2" x2="12" y2="6"></line>
                <line x1="12" y1="18" x2="12" y2="22"></line>
                <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"></line>
                <line x1="16.24" y1="16.24" x2="19.07" y2="19.07"></line>
                <line x1="2" y1="12" x2="6" y2="12"></line>
                <line x1="18" y1="12" x2="22" y2="12"></line>
                <line x1="4.93" y1="19.07" x2="7.76" y2="16.24"></line>
                <line x1="16.24" y1="7.76" x2="19.07" y2="4.93"></line>
              </svg>
            </template>
            {{ t('chat.cancelLoop') }}
          </n-button>
          <token-metrics-tooltip
            :input-tokens="agentStore.activeSubAgent.inputTokens || 0"
            :output-tokens="agentStore.activeSubAgent.outputTokens || 0"
            :cached-tokens="agentStore.activeSubAgent.cachedTokens"
            title="子代理上下文详情"
            :cid="agentStore.activeSubAgent?.cid ? Number(agentStore.activeSubAgent.cid) : undefined"
            :context-limit="contextLimit"
          >
            <template #default="{ total }">
              上下文窗口: <strong style="color: var(--text-color-bright); font-weight: bold;">{{ formatTokenCount(total) }}</strong><template v-if="contextLimitText"> / {{ contextLimitText }} ({{ usagePercentage(total) }}%)</template>
            </template>
          </token-metrics-tooltip>
        </div>
      </template>
      <div class="subagent-modal-content" :style="contentStyle">

      <!-- 独立消息列表内嵌区域：使用主会话聊天底色 var(--bg-color)，独立边框与滚动 -->
      <div class="subagent-chat-flow">
        <message-list-flow
          ref="messageListFlowRef"
          :messages="filteredMessages"
          :is-sub-agent="true"
          :cid="Number(agentStore.activeSubAgent?.cid)"
          :running="agentStore.activeSubAgent?.status === 'running'"
        />
      </div>
    </div>
  </n-card>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick, type CSSProperties } from 'vue'
import { useAgentStore } from '@/stores/agent'
import MessageListFlow from '@/views/chat/MessageListFlow.vue'
import TokenMetricsTooltip from '@/components/TokenMetricsTooltip.vue'
import SubAgentStatusTag from '@/components/SubAgentStatusTag.vue'
import { useResponsive } from '@/utils/useResponsive'
import { useContextWindow, formatTokenCount } from '@/composables/useContextWindow'
import { Message } from '@/types'
import { t } from '@/i18n'
import { buildRenderItems, type RenderItem } from '@/utils/foldEngine'

const agentStore = useAgentStore()
const { isMobile } = useResponsive()

// 上下文窗口口径：走共享 composable，恒取主会话绑定供应商的窗口大小（子会话继承父绑定）
const { contextLimit, contextLimitText, usagePercentage } = useContextWindow()

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
      width: 'min(85vw, 1200px) !important',
      maxWidth: '1200px',
      margin: 'auto !important'
    }
  }
})

const cardStyle = computed<CSSProperties>(() => {
  if (isMobile.value) {
    // 移动端真全屏：顶天立地，抹平边框与阴影
    return {
      width: '100% !important',
      height: '100svh',
      maxHeight: '100svh',
      display: 'flex',
      flexDirection: 'column',
      borderRadius: '0 !important',
      border: 'none !important',
      boxShadow: 'none !important',
      overflow: 'hidden'
    }
  }
  return {
    width: '100%',
    maxHeight: 'calc(100vh - 64px)',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden'
  }
})

const cardContentStyle = computed<CSSProperties>(() => {
  return {
    padding: isMobile.value ? '8px 12px 12px' : '16px 20px 20px',
    display: 'flex',
    flexDirection: 'column',
    flex: isMobile.value ? 1 : undefined,
    minHeight: 0,
    overflow: 'hidden'
  }
})

const cardHeaderStyle = computed<CSSProperties | undefined>(() => {
  return isMobile.value
    ? { padding: '12px 16px 8px 16px' }
    : undefined
})

const contentStyle = computed<CSSProperties>(() => {
  if (isMobile.value) {
    return {
      display: 'flex',
      flexDirection: 'column',
      flex: 1,
      minHeight: 0,
      width: '100%',
      height: '100%',
      boxSizing: 'border-box',
      paddingTop: '0',
      overflow: 'hidden'
    }
  } else {
    return {
      display: 'flex',
      flexDirection: 'column',
      height: '75vh',
      maxHeight: 'calc(100vh - 160px)',
      width: '100%',
      boxSizing: 'border-box',
      paddingTop: '0',
      overflow: 'hidden'
    }
  }
})

// 渲染序列类型：统一复用共享折叠引擎的 RenderItem（与主会话 ChatContainer 同一实现）
export type { RenderItem }

// 渲染序列组装：统一走共享折叠引擎（与主会话 ChatContainer 复用同一实现，含 FOLDED 虚拟块透传）
const filteredMessages = computed<RenderItem[]>(() => {
  const subAgent = agentStore.activeSubAgent;
  if (!subAgent || !subAgent.messages) return [];
  const result = buildRenderItems(subAgent.messages);
  if (subAgent.truncated) {
    result.unshift({ type: 'truncated_tip' });
  }
  return result;
});

const messageListFlowRef = ref<any>(null)

const scrollToBottom = (smooth = true) => {
  nextTick(() => {
    if (messageListFlowRef.value) {
      messageListFlowRef.value.scrollToBottom(smooth)
    }
  })
}

// 监听弹窗显示事件：四段式开窗贴底（nextTick、100ms、250ms、450ms 动效完全收敛后）
watch(() => agentStore.showSubAgentDrawer, (show) => {
  if (show) {
    scrollToBottom(false)
    setTimeout(() => {
      scrollToBottom(false)
    }, 100)
    setTimeout(() => {
      scrollToBottom(false)
    }, 250)
    setTimeout(() => {
      scrollToBottom(false)
    }, 450)
  }
})

// 监听活动子智能体切换：切换后也执行贴底
watch(() => agentStore.activeSubAgent?.cid, () => {
  if (agentStore.showSubAgentDrawer) {
    scrollToBottom(false)
    setTimeout(() => {
      scrollToBottom(false)
    }, 120)
  }
})

// 监听子代理消息条目变化：实时更新内嵌区域贴底
watch(
  () => filteredMessages.value.length,
  () => {
    if (agentStore.showSubAgentDrawer) {
      scrollToBottom(false)
    }
  }
)

// 深度监听末条消息内容流式推进：仅在运行推进时自动平滑/瞬时贴底
watch(
  () => {
    const msgs = filteredMessages.value
    if (!msgs || msgs.length === 0) return ''
    const last = msgs[msgs.length - 1]
    return last.type === 'message' ? (last.data.content || '') : ''
  },
  () => {
    if (agentStore.showSubAgentDrawer && agentStore.activeSubAgent?.status === 'running') {
      scrollToBottom(false)
    }
  }
)
</script>

<style scoped>
.subagent-modal-card :deep(.n-card-header) {
  background-color: var(--bg-color-modal) !important;
  border-bottom: 1px solid var(--border-color) !important;
}

.subagent-modal-card :deep(.n-card__content) {
  min-height: 0 !important;
  overflow: hidden !important;
}

.subagent-modal-content {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* 内嵌聊天区域：与主会话聊天底色 var(--bg-color) 保持一致，独立边框与滚动 */
.subagent-chat-flow {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  background-color: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  overflow: hidden;
}

.subagent-chat-flow :deep(.message-flow-list),
.subagent-chat-flow :deep(.virtual-scroller) {
  height: 100% !important;
  width: 100% !important;
  overflow-y: auto !important;
  box-sizing: border-box;
}

.subagent-modal-card {
  width: 100%;
  max-width: 100%;
  background-color: var(--bg-color-modal) !important;
  color: var(--text-color-bright) !important;
  border: 1px solid var(--border-color) !important;
  box-shadow: var(--shadow-overlay) !important;
  transition: all 0.3s ease;
}

/* 旋转图标动画（对齐主会话停止运行按钮样式） */
.spin-icon {
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
