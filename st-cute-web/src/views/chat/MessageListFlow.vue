<template>
  <virtual-chat-list
    ref="virtualScrollerRef"
    :items="messages"
    :estimated-item-size="100"
    :buffer="6"
    class="message-flow-list"
    :style="{ '--avatar-indent': appStore.showMessageAvatar ? '52px' : '0px' }"
  >
    <template #default="{ item, index }">
      <message-item
        v-if="item.type === 'message'"
        :message="item.data"
        :tools="item.tools"
        :is-sub-agent="isSubAgent"
        :cid="cid"
        :show-tail-dots="index === lastPendingBatchIndex"
        :show-empty-dots="index === lastEmptyAssistantIndex"
        :running="running"
      />
      <div
        v-else-if="item.type === 'tool_group'"
        class="tool-group-wrapper"
      >
        <tool-group-card
          :parent-message-id="item.parentMessageId"
          :tools="item.tools"
          :cid="cid"
          :show-tail-dots="index === lastPendingBatchIndex"
          :running="running"
        />
      </div>
      <div
        v-else-if="item.type === 'folded'"
        class="folded-wrapper"
      >
        <folded-message-card
          :folded="item.folded"
          :cid="cid"
        />
      </div>
      <div
        v-else-if="item.type === 'truncated_tip'"
        class="truncated-tip-wrapper"
      >
        <div class="glass-alert">
          <span class="icon">💡</span>
          <span class="text">更早的历史消息已被智能隐藏</span>
        </div>
      </div>
    </template>
  </virtual-chat-list>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import MessageItem from './MessageItem.vue'
import ToolGroupCard from './ToolGroupCard.vue'
import FoldedMessageCard from './FoldedMessageCard.vue'
import VirtualChatList from '@/components/VirtualChatList.vue'
import { Message } from '@/types'
import { useAppStore } from '@/stores/app'

// 应用全局状态：读取头像展示开关，动态控制工具卡片/折叠卡片的缩进变量
const appStore = useAppStore()

export type RenderItem =
  | { type: 'message'; data: Message; tools?: Message[] }
  | { type: 'tool_group'; parentMessageId: number | string; tools: Message[] }
  | { type: 'folded'; folded: Message }
  | { type: 'truncated_tip' }

const props = defineProps<{
  messages: RenderItem[]
  isSubAgent?: boolean
  cid?: number | null
  /**
   * 该消息列表所属会话是否处于运行中。必传——由各调用点按自身语义显式表态：
   * 主会话传全局 loopRunning，子会话抽屉传子代理运行态，折叠详情传 false（静态历史）。
   * 刻意不提供内部回退：回退到全局态在子会话场景本身就是错误语义，且会让调用方
   * 忘记传值时静默拿到错误结果；改为必传后由编译器强制表态。
   */
  running: boolean
}>()

const virtualScrollerRef = ref<any>(null)

/**
 * 末批工具组是否「尚未收口」：命中时返回该批所在渲染条目下标，否则返回 -1。
 *
 * 判定条件（三者同时成立）：
 * 1. 渲染序列末项是一批工具——助手消息携带 tools，或无父孤儿 tool_group；
 * 2. 该会话当前仍在运行（running）；
 * 3. 该批工具细则均已成功（无 RUNNING/PENDING/WAITING_APPROVAL 等未完成态）。
 *
 * 语义：工具都跑完了、批次仍停在消息列表末尾、而整个回合尚未收口 —— 此时模型正在
 * 消耗这批工具的产出做下一步推理（含等待子智能体回投结论后的汇总轮），故在卡片底部
 * 显示三点指示表示"这一批还在跑"。一旦模型产出下一轮消息，本批不再是末项，指示自然消失；
 * 运行态兼作守卫：用户取消回合或异常中断后立即熄灭，避免三点常亮误导。
 */
const lastPendingBatchIndex = computed(() => {
  const items = props.messages
  if (!items || items.length === 0) return -1
  if (!props.running) return -1

  const lastIndex = items.length - 1
  const lastItem = items[lastIndex]

  let batchTools: Message[] | undefined
  if (lastItem.type === 'message' && lastItem.tools && lastItem.tools.length > 0) {
    batchTools = lastItem.tools
  } else if (lastItem.type === 'tool_group') {
    batchTools = lastItem.tools
  }

  // 末项不是工具批次（如新增助手正文、折叠卡片等）时不显示
  if (!batchTools) return -1

  // 批内工具细则是否全部成功：任一条未达 SUCCESS（执行中/待审批/失败/取消）即不算收口。
  // 状态归一为大小写不敏感，避免后端字段大小写差异导致误判
  const allToolsSucceeded = batchTools.every(
    tool => (tool.status || '').toUpperCase() === 'SUCCESS'
  )
  return allToolsSucceeded ? lastIndex : -1
})

/**
 * 末条空助手消息所在渲染条目下标：命中时返回其下标（供 MessageItem 渲染思考中三点），否则返回 -1。
 *
 * 判定条件（三者同时成立）：
 * 1. 渲染序列末项是一条「消息」条目（而非折叠卡片、孤儿工具组等）；
 * 2. 该消息为助手类角色（ASSISTANT/BRANCH，排除 USER/SYSTEM；COMPRESSED 压缩占位消息有专属文案，不显示三点）；
 * 3. 其状态非完结（完结态 = SUCCESS/FAILED/CANCELED；空状态视为非完结），且正文与思考均为空。
 *
 * 语义：整个回合正在推进、模型还没吐出任何正文的「空白等待期」，用三点指示兜住空窗。
 * 刻意不叠加 running 守卫：移动端断线重连时 loopRunning 常常尚未同步回来（甚至信息加载失败始终为 false），
 * 若仍要求 running，末条空助手会退化成一片空白、用户完全看不到推进迹象；一旦正文到达或状态转终态即自然消失。
 */
const lastEmptyAssistantIndex = computed(() => {
  const items = props.messages
  if (!items || items.length === 0) return -1

  const lastIndex = items.length - 1
  const lastItem = items[lastIndex]
  if (lastItem.type !== 'message') return -1

  const msg = lastItem.data
  if (msg.role !== 'assistant' && msg.role !== 'branch') return -1
  // 状态归一为大小写不敏感：仅三种完结态不显示三点，其余（含空状态）一律视为推进中
  const status = (msg.status || '').toUpperCase()
  if (status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELED') return -1
  // 正文或思考任一已产出即让位于真实内容（思考内容由 MessageItem 另行渲染）
  if (msg.content || msg.thought) return -1

  return lastIndex
})

const scrollToBottom = (smooth = true) => {
  if (virtualScrollerRef.value) {
    virtualScrollerRef.value.scrollToBottom(smooth)
  }
}

const scrollToIndex = (index: number, smooth = true) => {
  if (virtualScrollerRef.value) {
    virtualScrollerRef.value.scrollToIndex(index, smooth)
  }
}

defineExpose({
  scrollToBottom,
  scrollToIndex
})
</script>

<style scoped>
.message-flow-list {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
}

.tool-group-wrapper {
  /* 缩进跟随头像开关收缩：展示头像时为 36px avatar + 16px gap，隐藏时收缩为 0（变量由根节点动态注入） */
  padding-left: var(--avatar-indent, 52px);
}

.folded-wrapper {
  /* 缩进跟随头像开关收缩：展示头像时为 36px avatar + 16px gap，隐藏时收缩为 0（变量由根节点动态注入） */
  padding-left: var(--avatar-indent, 52px);
  /* 与助手消息 .message-item-wrapper 同构的 flex 纵列：卡片宽度隐式收缩到内容宽（不再依赖卡片自身 fit-content），超宽时由卡片 max-width 封顶 */
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.truncated-tip-wrapper {
  padding: 16px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.glass-alert {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: var(--diff-add-bg);
  backdrop-filter: blur(8px);
  border: 1px dashed var(--success-green);
  border-radius: 8px;
  font-size: 13px;
  color: var(--n-text-color);
  animation: fadeIn 0.4s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>

