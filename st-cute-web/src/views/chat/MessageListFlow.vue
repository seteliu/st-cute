<template>
  <virtual-chat-list
    ref="virtualScrollerRef"
    :items="messages"
    :estimated-item-size="100"
    :buffer="6"
    class="message-flow-list"
    :style="{ '--avatar-indent': appStore.showMessageAvatar ? '52px' : '0px' }"
  >
    <template #default="{ item }">
      <message-item
        v-if="item.type === 'message'"
        :message="item.data"
        :tools="item.tools"
        :is-sub-agent="isSubAgent"
        :cid="cid"
      />
      <div
        v-else-if="item.type === 'tool_group'"
        class="tool-group-wrapper"
      >
        <tool-group-card
          :parent-message-id="item.parentMessageId"
          :tools="item.tools"
          :cid="cid"
        />
      </div>
      <div
        v-else-if="item.type === 'folded'"
        class="folded-wrapper"
      >
        <folded-message-card
          :folded-items="item.foldedItems"
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
  | { type: 'folded'; foldedItems: RenderItem[] }
  | { type: 'truncated_tip' }

defineProps<{
  messages: RenderItem[]
  isSubAgent?: boolean
  cid?: number | null
}>()

const virtualScrollerRef = ref<any>(null)

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
  background: rgba(24, 160, 88, 0.08);
  backdrop-filter: blur(8px);
  border: 1px dashed rgba(24, 160, 88, 0.3);
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

