<template>
  <n-layout-content class="center-content" bordered>
    <div class="chat-container">
      <!-- 聊天区头部 -->
      <div class="chat-header">
        <n-button
          quaternary
          circle
          size="small"
          style="margin-right: 8px;"
          @click="appStore.leftSiderCollapsed = !appStore.leftSiderCollapsed"
          title="控制左边栏"
        >
          <template #icon>
            <svg v-if="appStore.leftSiderCollapsed" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="9" y1="3" x2="9" y2="21"></line>
              <path d="M12 15l3-3-3-3"></path>
            </svg>
            <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="9" y1="3" x2="9" y2="21"></line>
              <path d="M15 9l-3 3 3 3"></path>
            </svg>
          </template>
        </n-button>

        <div class="chat-title-info" style="display: flex; align-items: center; gap: 12px;">
          <h3>{{ activeConversation ? activeConversation.title : '新会话' }}</h3>
        </div>

        <div class="chat-header-actions" style="margin-left: auto; margin-right: 15px;">
          <n-space align="center" style="font-size: 0.85rem; color: var(--text-color-muted);">
            <!-- 桌面端视图：Hover 提示 -->
            <token-metrics-tooltip
              v-if="!isMobile"
              :input-tokens="conversationStore.inputTokens"
              :output-tokens="conversationStore.outputTokens"
              :cached-tokens="conversationStore.cachedTokens"
              :title="t('chat.contextDetails')"
              :cid="conversationStore.activeCid ?? undefined"
              :context-limit="contextLimit"
            >
              <template #default="{ total }">
                {{ t('chat.contextWindow') }}: {{ formatTokenCount(total) }}<template v-if="contextLimitText"> / {{ contextLimitText }} (<strong style="color: var(--text-color-bright);">{{ usagePercentage(total) }}%</strong>)</template>
              </template>
            </token-metrics-tooltip>

            <!-- 移动端视图：点击触发，弹层内容与桌面端同一组件复用，仅触发方式不同。
                 【移动端特殊展示】移动端宽度紧张，触发文字精简为「上下文：xx.x%」仅百分比形式（完整明细点击弹层内查看）。
                 这是移动端为节省内容宽度特意设计的精简文案，不是缺陷，请勿改回桌面端的完整格式！ -->
            <token-metrics-tooltip
              v-else
              trigger="click"
              :input-tokens="conversationStore.inputTokens"
              :output-tokens="conversationStore.outputTokens"
              :cached-tokens="conversationStore.cachedTokens"
              :title="t('chat.contextDetails')"
              :cid="conversationStore.activeCid ?? undefined"
              :context-limit="contextLimit"
            >
              <template #default="{ total }">
                {{ t('chat.contextWindowShort') }}<strong style="color: var(--text-color-bright);">{{ usagePercentage(total) ?? '0.0' }}%</strong>
              </template>
            </token-metrics-tooltip>
          </n-space>
        </div>

        <div class="chat-status">
          <n-badge
            dot
            :type="appStore.isConnected ? 'success' : 'error'"
            :processing="currentTheme !== 'light' && appStore.isConnected"
          />
          <span>{{
            appStore.isConnected ? t('chat.connected') : t('chat.disconnected')
          }}</span>
        </div>

        <n-button
          quaternary
          circle
          size="small"
          style="margin-left: 8px;"
          @click="appStore.rightSiderCollapsed = !appStore.rightSiderCollapsed"
          title="控制右边栏"
        >
          <template #icon>
            <svg v-if="appStore.rightSiderCollapsed" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="15" y1="3" x2="15" y2="21"></line>
              <path d="M12 9l-3 3 3 3"></path>
            </svg>
            <svg v-else xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="15" y1="3" x2="15" y2="21"></line>
              <path d="M9 15l3-3-3-3"></path>
            </svg>
          </template>
        </n-button>
      </div>

      <!-- 消息列表展示 -->
      <n-spin
        :show="!appStore.isInitialized || conversationStore.isMessageLoading || conversationStore.isMessageSpinning"
        class="message-list"
        content-style="height: 100%; display: flex; flex-direction: column; min-height: 0;"
      >
        <div
          v-if="aggregatedMessages.length === 0 && !conversationStore.isMessageLoading"
          :class="[
            'empty-chat',
            'chat-flow-wrapper',
            (!appStore.isInitialized || conversationStore.isMessageLoading || conversationStore.isMessageSpinning) ? 'chat-flow-loading' : ''
          ]"
        >
          <span v-if="projectStore.projectList.length === 0">{{ t('chat.inputPlaceholderNoProject') }}</span>
          <span v-else-if="!projectStore.activeProjectId">{{ t('chat.inputPlaceholderNoActiveProject') }}</span>
          <span v-else-if="!conversationStore.activeCid">{{ t('chat.inputPlaceholderNoCid') }}</span>
          <span v-else>{{ t('chat.inputPlaceholder') }}</span>
        </div>
        <message-list-flow
          ref="messageListFlowRef"
          :messages="aggregatedMessages"
          :cid="conversationStore.activeCid"
          :running="appStore.loopRunning"
          :class="[
            'chat-flow-wrapper',
            (!appStore.isInitialized || conversationStore.isMessageLoading || conversationStore.isMessageSpinning) ? 'chat-flow-loading' : ''
          ]"
        />
      </n-spin>

      <!-- 底部输入框 -->
      <chat-input />
    </div>
  </n-layout-content>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useProjectStore } from '@/stores/project'
import { useProviderStore } from '@/stores/provider'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'
import { currentTheme } from '@/styles/theme'
import MessageListFlow from './MessageListFlow.vue'
import ChatInput from './ChatInput.vue'
import TokenMetricsTooltip from '@/components/TokenMetricsTooltip.vue'
import { useContextWindow, formatTokenCount } from '@/composables/useContextWindow'
import { buildRenderItems, type RenderItem } from '@/utils/foldEngine'

const appStore = useAppStore()
const conversationStore = useConversationStore()
const projectStore = useProjectStore()

const { isMobile } = useResponsive()

const messageListFlowRef = ref<any>(null)

// 渲染序列组装：统一走共享折叠引擎（R1 预处理 + FOLDED 虚拟块透传），折叠算法后端已按 R1~R4 生成
const aggregatedMessages = computed<RenderItem[]>(() => {
  const result = buildRenderItems(conversationStore.messages)
  if (conversationStore.truncated) {
    result.unshift({ type: 'truncated_tip' })
  }
  return result
})

const activeConversation = computed(() => {
  const activeId = conversationStore.activeCid
  return conversationStore.conversationList.find(s => s.id === activeId)
})

// 上下文窗口口径：走共享 composable（三处弹层统一取值，主会话绑定供应商的 contextSize）
const { contextLimit, contextLimitText, usagePercentage } = useContextWindow()

const scrollToBottom = (smooth = true) => {
  nextTick(() => {
    if (messageListFlowRef.value) {
      messageListFlowRef.value.scrollToBottom(smooth)
    }
  })
}

</script>

<style scoped>
.empty-chat {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  color: var(--text-color-muted);
  font-style: italic;
  font-size: 0.95rem;
}

.chat-flow-wrapper {
  transition: opacity 0.22s ease, transform 0.22s ease;
  opacity: 1;
  transform: translateY(0);
  width: 100%;
}

/* 消息流高度撑开 */
.message-list :deep(message-list-flow.chat-flow-wrapper),
.chat-flow-wrapper:not(.empty-chat) {
  height: 100%;
}

.chat-flow-loading {
  opacity: 0 !important;
  transform: translateY(6px) !important;
  pointer-events: none;
  transition: none !important; /* 瞬间静默隐去，排除旧内容淡出的位移晃动与明暗闪变 */
}
</style>
