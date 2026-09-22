<template>
  <n-modal
    v-model:show="agentStore.showSubAgentDrawer"
    :style="modalStyle"
  >
    <n-card
      v-if="agentStore.activeSubAgent"
      :class="['subagent-modal-card', agentStore.activeSubAgent.status?.toLowerCase()]"
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
          <div v-if="isMobile" style="display: flex; align-items: center; gap: 12px; margin-top: 6px; font-size: 0.8rem; color: #a0a0a5; font-weight: 500;">
            <token-metrics-tooltip
              :input-tokens="agentStore.activeSubAgent.inputTokens || 0"
              :output-tokens="agentStore.activeSubAgent.outputTokens || 0"
              :cached-tokens="agentStore.activeSubAgent.cachedTokens"
              title="子代理上下文详情"
            >
              <template #default="{ total }">
                上下文窗口: <strong style="color: var(--text-color-bright); font-weight: bold;">{{ total }}</strong>
              </template>
            </token-metrics-tooltip>

            <n-button
              v-if="agentStore.activeSubAgent.status === 'running'"
              size="tiny"
              type="error"
              ghost
              @click="agentStore.handleKillMember"
            >
              {{ t('chat.cancelLoop') }}
            </n-button>
            <n-popconfirm
              v-if="agentStore.activeSubAgent.status === 'success'"
              @positive-click="agentStore.deleteSubAgent(Number(agentStore.activeSubAgent.cid))"
              :positive-text="t('common.confirm')"
              :negative-text="t('common.cancel')"
            >
              <template #trigger>
                <n-button size="tiny" type="error" ghost>
                  {{ t('common.delete') }}
                </n-button>
              </template>
              {{ t('sider.deleteConfirmContent') }}
            </n-popconfirm>
          </div>
        </div>
      </template>
      <template #header-extra v-if="!isMobile">
        <div style="display: flex; align-items: center; gap: 12px; padding-right: 12px; font-size: 0.85rem; color: #a0a0a5; font-weight: 500;">
          <n-button
            v-if="agentStore.activeSubAgent.status === 'running'"
            size="tiny"
            type="error"
            ghost
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
          <n-popconfirm
            v-if="agentStore.activeSubAgent.status === 'success'"
            @positive-click="agentStore.deleteSubAgent(Number(agentStore.activeSubAgent.cid))"
            :positive-text="t('common.confirm')"
            :negative-text="t('common.cancel')"
          >
            <template #trigger>
              <n-button
                size="tiny"
                type="error"
                ghost
              >
                <template #icon>
                  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="3 6 5 6 21 6"></polyline>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    <line x1="10" y1="11" x2="10" y2="17"></line>
                    <line x1="14" y1="11" x2="14" y2="17"></line>
                  </svg>
                </template>
                {{ t('common.delete') }}
              </n-button>
            </template>
            {{ t('sider.deleteConfirmContent') }}
          </n-popconfirm>
          <token-metrics-tooltip
            :input-tokens="agentStore.activeSubAgent.inputTokens || 0"
            :output-tokens="agentStore.activeSubAgent.outputTokens || 0"
            :cached-tokens="agentStore.activeSubAgent.cachedTokens"
            title="子代理上下文详情"
          >
            <template #default="{ total }">
              上下文窗口: <strong style="color: var(--text-color-bright); font-weight: bold;">{{ total }}</strong>
            </template>
          </token-metrics-tooltip>
        </div>
      </template>
      <div class="subagent-modal-content" :style="contentStyle">

      <!-- 独立消息列表 -->
      <div style="flex: 1; min-height: 0; margin-bottom: 12px; width: 100%; box-sizing: border-box;" class="subagent-chat-flow">
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
import { Message } from '@/types'
import { t } from '@/i18n'
import { buildRenderItems, type RenderItem } from '@/utils/foldEngine'

const agentStore = useAgentStore()
const { isMobile } = useResponsive()

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
      width: '80vw !important'
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
      boxSizing: 'border-box',
      paddingTop: '0'
    }
  } else {
    return {
      display: 'flex',
      flexDirection: 'column',
      height: '75vh',
      width: '100%',
      boxSizing: 'border-box',
      paddingTop: '8px'
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

// 监听弹窗显示事件
watch(() => agentStore.showSubAgentDrawer, (show) => {
  if (show) {
    scrollToBottom(false)
    setTimeout(() => {
      scrollToBottom(false)
    }, 150)
  }
})
</script>

<style scoped>
.subagent-modal-card :deep(.n-card-header) {
  background-color: var(--bg-color-card) !important;
  border-bottom: 1px solid var(--border-color) !important;
}

.subagent-modal-content {
  overflow: hidden;
}
.subagent-chat-flow {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.subagent-modal-card {
  width: 100% !important;
  max-width: 100% !important;
  background-color: #18181c !important;
  color: #fff !important;
  transition: all 0.3s ease;
}

/* 大卡片的状态发光特效 */
.subagent-modal-card.running {
  border: 1.5px solid var(--border-color-active) !important;
  box-shadow: 0 0 20px rgba(129, 182, 229, 0.4) !important;
}

.subagent-modal-card.success {
  border: 1.5px solid var(--border-color-active) !important;
  box-shadow: 0 0 20px rgba(129, 182, 229, 0.4) !important;
}

.subagent-modal-card.failed {
  border: 1.5px solid rgba(208, 48, 80, 0.7) !important;
  box-shadow: 0 0 20px rgba(208, 48, 80, 0.3) !important;
}

/* 遥测卡片状态化微边框（微弱边界） */
.telemetry-card {
  padding: 12px;
  background-color: var(--bg-color);
  border-radius: 6px;
  margin-bottom: 12px;
  font-size: 12px;
  line-height: 1.6;
  transition: all 0.3s ease;
}

.telemetry-card.running {
  border: 1px solid var(--border-color-active);
}

.telemetry-card.success {
  border: 1px solid var(--border-color-active);
}

.telemetry-card.failed {
  border: 1px solid rgba(208, 48, 80, 0.3);
}
</style>
