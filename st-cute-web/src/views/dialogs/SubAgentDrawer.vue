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
        />
      </div>

      <!-- 独立人在回路（HITL）审批区 -->
      <div
        v-if="agentStore.activeSubAgent.pendingPermissionReq"
        style="padding: 12px; background-color: #2b1f1f; border: 1px solid #d03050; border-radius: 6px; margin-top: auto;"
      >
        <div
          style="font-weight: bold; color: #d03050; margin-bottom: 6px; display: flex; align-items: center; gap: 4px;"
        >
          <span>⚠️</span> <span>人在回路审批拦截</span>
        </div>
        <div style="font-size: 12px; margin-bottom: 8px;">
          子代理请求执行敏感工具:
          <strong style="color: var(--status-warning);">{{
            agentStore.activeSubAgent.pendingPermissionReq.toolName
          }}</strong>
          <div
            style="margin-top: 8px; display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;"
          >
            <span style="color: #a0a0a5; font-size: 11px;">参数信息:</span>
            <n-switch
              v-model:value="agentStore.activeSubAgent.pendingPermissionReq.isEditingArgs"
              size="small"
            >
              <template #checked>编辑参数</template>
              <template #unchecked>开启编辑</template>
            </n-switch>
          </div>
          <div>
            <n-input
              v-if="agentStore.activeSubAgent.pendingPermissionReq.isEditingArgs"
              v-model:value="agentStore.activeSubAgent.pendingPermissionReq.editedArgumentsJson"
              type="textarea"
              placeholder="请输入修改后的参数 (JSON格式)"
              :autosize="{ minRows: 2, maxRows: 6 }"
              style="font-family: monospace; font-size: 11px; background-color: #101014; border-color: #2d2d30; color: #fff;"
              maxlength="10000"
            />
            <pre
              v-else
              style="background-color: #101014; padding: 6px; border: 1px solid #2d2d30; border-radius: 4px; font-size: 11px; color: #a0a0a5; max-height: 100px; overflow-y: auto; margin-top: 4px; white-space: pre-wrap;"
              >{{
                formatArgumentsJson(agentStore.activeSubAgent.pendingPermissionReq.arguments)
              }}</pre
            >
          </div>
        </div>
        <div style="display: flex; justify-content: flex-end; gap: 8px;">
          <n-button
            size="small"
            type="error"
            @click="agentStore.handleSubPermissionDecision(agentStore.activeSubAgent, 'DENY')"
            >拒绝 (Deny)</n-button
          >
          <n-button
            size="small"
            type="primary"
            @click="agentStore.handleSubPermissionDecision(agentStore.activeSubAgent, 'ALLOW')"
            >允许 (Allow)</n-button
          >
        </div>
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

const agentStore = useAgentStore()
const { isMobile } = useResponsive()

const modalStyle = computed<CSSProperties>(() => {
  if (isMobile.value) {
    return {
      width: '100vw !important',
      height: '100vh !important',
      maxHeight: '100vh !important',
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
    return {
      height: '100vh',
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

export type RenderItem = 
  | { type: 'message'; data: Message; tools?: Message[] } 
  | { type: 'tool_group'; parentMessageId: number | string; tools: Message[] }

const filteredMessages = computed<RenderItem[]>(() => {
  const subAgent = agentStore.activeSubAgent;
  if (!subAgent || !subAgent.messages) return [];
  const rawMsgs = subAgent.messages;
  
  // 1. 拆分普通消息与工具消息，将工具消息按 parentMessageId 分类
  const normalMsgs: Message[] = [];
  const toolMap = new Map<string | number, Message[]>();

  for (const msg of rawMsgs) {
    if (msg.role === 'tool') {
      const pId = msg.parentMessageId || 'orphan_sub_tools';
      if (!toolMap.has(pId)) {
        toolMap.set(pId, []);
      }
      toolMap.get(pId)!.push(msg);
    } else {
      normalMsgs.push(msg);
    }
  }

  // 2. 组装并嵌套注入工具列表，保持子代理中的头像不消失
  const result: RenderItem[] = [];
  for (const msg of normalMsgs) {
    if (msg.role === 'system') {
      continue;
    }

    const msgTools = toolMap.get(msg.id) || [];
    const hasTools = msgTools.length > 0;

    // 过滤无内容、无工具调用、且已成功（SUCCESS）的助手空消息（放行 CANCELED、FAILED、RUNNING、PENDING 等需展示状态的卡片）
    if (msg.role === 'assistant' && !msg.content && !msg.thought && !msg.isStreaming && msg.status === 'SUCCESS' && !hasTools) {
      continue;
    }

    result.push({
      type: 'message',
      data: msg,
      tools: hasTools ? msgTools : undefined
    });
  }

  // 兜底渲染没有任何父消息的孤儿工具消息
  if (toolMap.has('orphan_sub_tools')) {
    result.push({
      type: 'tool_group',
      parentMessageId: 'orphan_sub_tools',
      tools: toolMap.get('orphan_sub_tools')!
    });
  }

  if (subAgent.truncated) {
    result.unshift({
      type: 'truncated_tip'
    } as any);
  }

  return result;
});

const formatArgumentsJson = (argsStr: string | undefined) => {
  if (!argsStr) return ''
  try {
    const parsed = JSON.parse(argsStr)
    return JSON.stringify(parsed, null, 2)
  } catch (e) {
    return argsStr
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
