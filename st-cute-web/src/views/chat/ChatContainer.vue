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
          <n-space align="center" style="font-size: 0.85rem; color: #a0a0a5;">
            <!-- 桌面端视图：Hover 提示 -->
            <token-metrics-tooltip
              v-if="!isMobile"
              :input-tokens="conversationStore.inputTokens"
              :output-tokens="conversationStore.outputTokens"
              :cached-tokens="conversationStore.cachedTokens"
              :title="t('chat.contextDetails')"
            >
              <template #default="{ total }">
                {{ t('chat.contextWindow') }}: <strong style="color: var(--text-color-bright);">{{ total }}</strong> / {{ contextLimitText }} ({{
                  usagePercentage(total)
                }}%)
              </template>
            </token-metrics-tooltip>

            <!-- 移动端视图：点击 Popover -->
            <n-popover v-else trigger="click" placement="bottom" style="background-color: #18181c;">
              <template #trigger>
                <span class="mobile-token-pct">
                  {{ t('chat.contextWindow') }}: {{ usagePercentage(totalTokens) }}%
                </span>
              </template>
              <div style="font-size: 0.8rem; line-height: 1.6; padding: 4px; color: #e3e3e7;">
                <div style="font-weight: bold; border-bottom: 1px solid #444; margin-bottom: 6px; padding-bottom: 4px;">
                  {{ t('chat.contextDetails') }}
                </div>
                <div>Total: <strong>{{ totalTokens }}</strong> / {{ contextLimitText }}</div>
                <div>{{ t('chat.inputToken') }} (Input): {{ conversationStore.inputTokens }}</div>
                <div>{{ t('chat.outputToken') }} (Output): {{ conversationStore.outputTokens }}</div>
                <div>{{ t('chat.cachedToken') }} (Cached): {{ conversationStore.cachedTokens !== undefined ? conversationStore.cachedTokens : 0 }}</div>
                <div v-if="conversationStore.inputTokens > 0 && conversationStore.cachedTokens" style="color: var(--status-warning); font-weight: bold; margin-top: 4px; border-top: 1px dashed #444; padding-top: 4px;">
                  {{ t('chat.cacheRatio') }} (Ratio): {{ ((conversationStore.cachedTokens / conversationStore.inputTokens) * 100).toFixed(1) }}%
                </div>
              </div>
            </n-popover>
          </n-space>
        </div>

        <div class="chat-status">
          <n-badge
            dot
            :type="appStore.isConnected ? 'success' : 'error'"
            :processing="appStore.isConnected"
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
import { useMessage } from 'naive-ui'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useProjectStore } from '@/stores/project'
import { useProviderStore } from '@/stores/provider'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'
import MessageListFlow from './MessageListFlow.vue'
import ChatInput from './ChatInput.vue'
import TokenMetricsTooltip from '@/components/TokenMetricsTooltip.vue'
import { buildRenderItems, type RenderItem } from '@/utils/foldEngine'

const appStore = useAppStore()
const conversationStore = useConversationStore()
const projectStore = useProjectStore()
const providerStore = useProviderStore()
const message = useMessage()

const { isMobile } = useResponsive()

const isReloading = ref(false)

const totalTokens = computed(() => {
  return (conversationStore.inputTokens || 0) + (conversationStore.outputTokens || 0)
})

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

const contextLimit = computed(() => {
  const activeConv = activeConversation.value
  if (activeConv && activeConv.providerGroup) {
    const provider = providerStore.providerList.find(p => p.group === activeConv.providerGroup)
    if (provider && provider.contextSize) {
      return provider.contextSize
    }
  }
  return 100000
})

const contextLimitText = computed(() => {
  const limit = contextLimit.value
  return limit >= 1000 ? `${(limit / 1000).toFixed(0)}K` : `${limit}`
})

const usagePercentage = computed(() => {
  return (total: number) => {
    const limit = contextLimit.value
    if (!limit) return '0.0'
    return ((total / limit) * 100).toFixed(1)
  }
})

const handleReloadConfig = async () => {
  if (isReloading.value) return
  isReloading.value = true
  try {
    const success = await conversationStore.reloadProjectAssets()
    if (success) {
      message.success('项目专属 Skills、Hook 与 MCP 工具已成功热重载')
    } else {
      message.error('热重载失败，请检查后端日志或物理配置是否正确')
    }
  } catch (e) {
    message.error('热重载过程发生异常')
  } finally {
    isReloading.value = false
  }
}

const messageListFlowRef = ref<any>(null)

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
  color: var(--text-color-secondary);
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
