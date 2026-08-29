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
          v-if="filteredMessages.length === 0 && !conversationStore.isMessageLoading"
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

export type RenderItem =
  | { type: 'message'; data: any; tools?: any[] }
  | { type: 'tool_group'; parentMessageId: number | string; tools: any[] }

const filteredMessages = computed<RenderItem[]>(() => {
  const rawMsgs = conversationStore.messages

  // 1. 拆分普通消息与工具消息，将工具消息按 parentMessageId 分类
  const normalMsgs: any[] = []
  const toolMap = new Map<string | number, any[]>()

  for (const msg of rawMsgs) {
    if (msg.role === 'tool') {
      const pId = msg.parentMessageId || 'orphan_tools'
      if (!toolMap.has(pId)) {
        toolMap.set(pId, [])
      }
      toolMap.get(pId)!.push(msg)
    } else {
      normalMsgs.push(msg)
    }
  }

  // 2. 组装消息列表，将工具列表作为 tools 注入父消息，实现完美嵌套，防止头像丢失
  const result: RenderItem[] = []
  for (const msg of normalMsgs) {
    if (msg.role === 'system') {
      continue
    }

    const msgTools = toolMap.get(msg.id) || []
    const hasTools = msgTools.length > 0

    // 过滤无内容、无工具调用、且已成功（SUCCESS）的助手空消息（放行 CANCELED、FAILED、RUNNING、PENDING 等需展示状态的卡片）
    if (msg.role === 'assistant' && !msg.content && !msg.thought && !msg.isStreaming && msg.status === 'SUCCESS' && !hasTools) {
      continue
    }

    result.push({
      type: 'message',
      data: msg,
      tools: hasTools ? msgTools : undefined
    })
  }

  // 兜底渲染没有任何父消息的孤儿工具消息
  if (toolMap.has('orphan_tools')) {
    result.push({
      type: 'tool_group',
      parentMessageId: 'orphan_tools',
      tools: toolMap.get('orphan_tools')!
    })
  }

  return result
})

const isMessageSuccess = (msg: any): boolean => {
  if (!msg) return false
  const status = msg.status || 'SUCCESS'
  return status === 'SUCCESS'
}

const isRenderItemSuccess = (item: RenderItem): boolean => {
  if (item.type !== 'message') {
    return false
  }
  const msg = item.data
  if (!msg) return false
  if (!isMessageSuccess(msg)) return false
  if (item.tools && item.tools.length > 0) {
    for (const tool of item.tools) {
      if (!isMessageSuccess(tool)) return false
    }
  }
  return true
}

const isRenderItemWaitingApproval = (item: RenderItem): boolean => {
  if (item.type === 'message') {
    const msg = item.data
    if (msg && msg.status === 'WAITING_APPROVAL') return true
    if (item.tools && item.tools.length > 0) {
      return item.tools.some((t: any) => t.status === 'WAITING_APPROVAL')
    }
  } else if (item.type === 'tool_group') {
    if (item.tools && item.tools.length > 0) {
      return item.tools.some((t: any) => t.status === 'WAITING_APPROVAL')
    }
  }
  return false
}

const isRenderItemRunning = (item: RenderItem): boolean => {
  if (item.type === 'message') {
    const msg = item.data
    if (msg && (msg.status === 'RUNNING' || msg.status === 'PENDING')) return true
    if (item.tools && item.tools.length > 0) {
      return item.tools.some((t: any) => t.status === 'RUNNING' || t.status === 'PENDING')
    }
  } else if (item.type === 'tool_group') {
    if (item.tools && item.tools.length > 0) {
      return item.tools.some((t: any) => t.status === 'RUNNING' || t.status === 'PENDING')
    }
  }
  return false
}

const isTerminalAssistant = (item: RenderItem): boolean => {
  if (item.type !== 'message') return false
  const msg = item.data
  if (!msg) return false
  // 角色匹配（助手 / 分支 / 压缩）
  const isAssistantRole = msg.role === 'assistant' || msg.role === 'branch' || msg.role === 'compressed'
  if (!isAssistantRole) return false
  // 达到终态（成功 / 失败 / 取消）且不再处于流式传输中（支持无 content 的纯工具调用助手）
  const isTerminalStatus = msg.status === 'SUCCESS' || msg.status === 'FAILED' || msg.status === 'CANCELED'
  return isTerminalStatus && !msg.isStreaming
}

const aggregatedMessages = computed<RenderItem[]>(() => {
  const rawFiltered = filteredMessages.value
  if (!appStore.messageAggregation) {
    return rawFiltered
  }

  const result: RenderItem[] = []
  let i = 0
  const len = rawFiltered.length

  while (i < len) {
    const segment: RenderItem[] = []

    // 如果当前是 user 消息，先放入段中
    const first = rawFiltered[i]
    if (first.type === 'message' && (first as any).data.role === 'user') {
      segment.push(first)
      i++
    }

    // 收集后续所有的非 user 消息，直到下一个 user 消息
    while (i < len) {
      const current = rawFiltered[i]
      if (current.type === 'message' && (current as any).data.role === 'user') {
        break
      }
      segment.push(current)
      i++
    }

    if (segment.length <= 1) {
      result.push(...segment)
      continue
    }

    let hasUser = false
    let userItem: RenderItem | null = null
    let nonUserItems: RenderItem[] = []

    if (segment[0].type === 'message' && segment[0].data.role === 'user') {
      hasUser = true
      userItem = segment[0]
      nonUserItems = segment.slice(1)
    } else {
      nonUserItems = segment
    }

    // 1. 查找首个「待审批」消息在 nonUserItems 中的索引
    let firstWaitingIndex = -1
    for (let k = 0; k < nonUserItems.length; k++) {
      if (isRenderItemWaitingApproval(nonUserItems[k])) {
        firstWaitingIndex = k
        break
      }
    }

    const toFold: RenderItem[] = []
    const toKeep: RenderItem[] = []

    if (firstWaitingIndex !== -1) {
      // 规则 1：只要有待审批的，从它开始及之后的所有消息全部保持外露（toKeep），严禁折叠
      // 仅待审批之前的已完成前置历史步骤放入 toFold
      for (let k = 0; k < firstWaitingIndex; k++) {
        toFold.push(nonUserItems[k])
      }
      for (let k = firstWaitingIndex; k < nonUserItems.length; k++) {
        toKeep.push(nonUserItems[k])
      }
    } else {
      // 规则 2：无待审批时，最后一条终态助手消息及之后的所有消息全部保持外露（toKeep）
      // 查找最后一条终态助手的索引（从后向前寻找）
      let lastTerminalIndex = -1
      for (let k = nonUserItems.length - 1; k >= 0; k--) {
        if (isTerminalAssistant(nonUserItems[k])) {
          lastTerminalIndex = k
          break
        }
      }

      if (lastTerminalIndex !== -1) {
        for (let k = 0; k < lastTerminalIndex; k++) {
          toFold.push(nonUserItems[k])
        }
        for (let k = lastTerminalIndex; k < nonUserItems.length; k++) {
          toKeep.push(nonUserItems[k])
        }
      } else {
        // 整轮刚开始运行尚未产生任何终态助手，全量平铺外露
        toKeep.push(...nonUserItems)
      }
    }

    // 2. 组合最终展示序列。只要存在前置可折叠的中间步骤（toFold.length > 0），即生成折叠卡片收纳
    const processedNonUser: RenderItem[] = []
    if (toFold.length > 0) {
      processedNonUser.push({
        type: 'folded',
        foldedItems: toFold
      } as any)
      processedNonUser.push(...toKeep)
    } else {
      processedNonUser.push(...nonUserItems)
    }

    if (hasUser && userItem) {
      result.push(userItem)
    }
    result.push(...processedNonUser)
  }

  if (conversationStore.truncated) {
    result.unshift({
      type: 'truncated_tip'
    } as any)
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
