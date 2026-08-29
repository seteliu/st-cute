<template>
  <div :class="['message-item-wrapper', message.role + '-wrapper']">
    <div
      :class="[
        'message',
        message.role === 'user'
          ? 'user-message'
          : message.role === 'system'
          ? 'system-message'
          : message.role === 'branch'
          ? 'branch-message'
          : message.role === 'compressed'
          ? 'compressed-message'
          : 'assistant-message',
        'status-' + (message.status || 'SUCCESS').toLowerCase()
      ]"
    >
      <template v-if="message.role !== 'system'">
        <!-- 角色头像：受全局开关 showMessageAvatar 控制（默认关闭），关闭后不渲染，flex gap 间隙随之自动收缩 -->
        <div v-if="appStore.showMessageAvatar" class="avatar">{{ avatarLabel }}</div>
        <div class="msg-content-wrapper">
          <div 
            v-if="message.content || message.thought || message.attachments || message.status === 'FAILED' || message.status === 'CANCELED' || ((message.isStreaming || message.status === 'RUNNING' || message.status === 'PENDING') && !message.content && !message.thought)" 
            class="msg-content"
          >
            <!-- 附件区域展示 -->
            <message-attachments v-if="message.attachments" :attachments="message.attachments" />

            <!-- 思考过程单行精简展示 -->
            <div v-if="message.thought" class="thought-row">
              <span class="thought-prefix">思考过程:</span>
              <span class="thought-text-fixed">{{ cleanThoughtText }}</span>
              <span class="thought-count-tag">(共 {{ thoughtCharCount }} 字)</span>
              <n-button
                size="tiny"
                quaternary
                type="primary"
                class="thought-detail-btn"
                @click="appStore.openThoughtDetail(message.thought, message.id)"
              >
                {{ t('chat.viewThoughtDetail') }}
              </n-button>
            </div>

            <!-- 正文内容 (简单渲染 HTML 换行以支持纯文本换行) -->
            <div v-if="message.role === 'branch'" class="branch-label">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="4 17 10 11 4 5"></polyline>
                <line x1="12" y1="19" x2="20" y2="19"></line>
              </svg>
              SubAgent Report
            </div>
            
            <div v-if="message.role === 'compressed'" class="compressed-status-text">
              <span v-if="message.status === 'RUNNING' || message.status === 'PENDING'">上下文压缩中...</span>
              <span v-else-if="message.status === 'SUCCESS'">上下文压缩完成</span>
              <span v-else-if="message.status === 'FAILED'">上下文压缩失败</span>
              <span v-else>上下文压缩状态: {{ message.status }}</span>
            </div>
            <div v-else v-html="formattedContent"></div>
            <!-- 思考中指示 (大模型正在运行/加载且尚未有输出内容时，显示三个小点跳动动效) -->
            <div
              v-if="message.role !== 'compressed' && (message.isStreaming || message.status === 'RUNNING' || message.status === 'PENDING') && !message.content && !message.thought"
              class="thinking-animation"
            >
              <span class="thinking-dot"></span>
              <span class="thinking-dot"></span>
              <span class="thinking-dot"></span>
            </div>

            <!-- 取消/失败/等待等文字标志 -->
            <div v-if="message.status === 'FAILED'" style="color: var(--status-error); font-size: 0.8rem; margin-top: 6px; display: flex; align-items: center; gap: 4px;">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="12" y1="8" x2="12" y2="12"></line>
                <line x1="12" y1="16" x2="12.01" y2="16"></line>
              </svg>
              执行失败，您可以点击下方的重试按钮。
            </div>
            <div v-if="message.status === 'CANCELED'" style="color: var(--text-color-secondary); font-size: 0.8rem; margin-top: 6px; display: flex; align-items: center; gap: 4px;">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="15" y1="9" x2="9" y2="15"></line>
                <line x1="9" y1="9" x2="15" y2="15"></line>
              </svg>
              已手动终止运行。
            </div>
          </div>

          <!-- 工具调用卡片直接嵌套在消息内容中，保证头像 A 常驻不消失 -->
          <div v-if="tools && tools.length > 0" class="nested-tool-group-wrapper">
            <tool-group-card
              :parent-message-id="message.id"
              :tools="tools"
              :cid="cid !== undefined && cid !== null ? cid : conversationStore.activeCid"
            />
          </div>

          <!-- 功能操作栏 -->
          <div class="message-action-bar">
            <!-- 左侧：消息时间 -->
            <div class="message-time">
              {{ displayTime }}
            </div>

            <!-- 右侧：功能按钮 -->
            <div class="message-actions-buttons">
              <!-- 重置到此节点按钮（仅限用户消息，且当前会话未在进行中时可点击，使用与删除会话一致的气泡确认框样式） -->
              <n-popconfirm
                v-if="message.role === 'user'"
                @positive-click="handleRollback"
                positive-text="确认"
                negative-text="取消"
                placement="top"
              >
                <template #trigger>
                  <n-button
                    quaternary
                    circle
                    size="tiny"
                    title="重置到此节点"
                    :disabled="appStore.loopRunning"
                  >
                    <template #icon>
                      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="9 14 4 9 9 4"></polyline>
                        <path d="M20 20v-7a4 4 0 0 0-4-4H4"></path>
                      </svg>
                    </template>
                  </n-button>
                </template>
                <div style="white-space: normal; max-width: 220px; line-height: 1.4;">
                  确定重置至此节点吗？
                  <div style="font-size: 0.75rem; opacity: 0.75; margin-top: 4px;">
                    后续消息将被删除，不可恢复。
                  </div>
                </div>
              </n-popconfirm>

              <!-- 复制按钮 (常驻) -->
              <n-button
                quaternary
                circle
                size="tiny"
                title="复制消息内容"
                @click="handleCopy"
              >
                <template #icon>
                  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                  </svg>
                </template>
              </n-button>

              <!-- 重试按钮 (仅在非子代理，且失败状态下显示，且只允许最后一个用户消息之后的消息可以重试) -->
              <n-button
                v-if="canRetry"
                quaternary
                circle
                size="tiny"
                type="warning"
                title="重试此步骤"
                @click="handleRetry"
              >
                <template #icon>
                  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"></path>
                  </svg>
                </template>
              </n-button>
            </div>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="system-banner">
          {{ message.content }}
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { marked } from 'marked'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { Message } from '@/types'
import { t } from '@/i18n'
import ToolGroupCard from './ToolGroupCard.vue'
import MessageAttachments from './MessageAttachments.vue'

const props = defineProps<{
  message: Message
  tools?: Message[]
  isSubAgent?: boolean
  cid?: number | null
}>()

const appStore = useAppStore()
const conversationStore = useConversationStore()

// 思考过程纯文本清洗与实时字数统计
const cleanThoughtText = computed(() => {
  return (props.message.thought || '').replace(/\s+/g, ' ').trim()
})

const thoughtCharCount = computed(() => {
  return (props.message.thought || '').length
})

const avatarLabel = computed(() => {
  if (props.message.role === 'user') return 'U'
  if (props.message.role === 'branch') return 'B'
  if (props.message.role === 'compressed') return 'C'
  return 'A'
})

// 只允许最后一个用户消息之后的消息可以重试，防止历史消息中重试导致上下文错乱
const canRetry = computed(() => {
  if (props.isSubAgent || props.message.status !== 'FAILED') {
    return false
  }
  const userMessages = conversationStore.messages.filter(m => m.role === 'user')
  if (userMessages.length === 0) {
    return true
  }
  const lastUserMsg = userMessages[userMessages.length - 1]
  return (props.message.id || 0) >= (lastUserMsg.id || 0)
})

const formattedContent = computed(() => {
  const text = props.message.content
  if (!text) return ''
  
  let targetText = text
  if (props.message.role === 'branch') {
    const idx = text.indexOf('结果摘要:')
    if (idx !== -1) {
      targetText = text.substring(0, idx).trim()
    }
  }

  try {
    // 使用 marked 同步解析 markdown
    return marked.parse(targetText, { async: false, gfm: true, breaks: true }) as string
  } catch (e) {
    console.error('Markdown 解析错误:', e)
    return targetText
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
  }
})

const handleCopy = () => {
  const text = props.message.content || ''
  navigator.clipboard.writeText(text).then(() => {
    if ((window as any).$message) {
      (window as any).$message.success('消息内容已复制')
    }
  }).catch((e) => {
    console.error('复制失败:', e)
  })
}

const handleRollback = async () => {
  const text = props.message.content || ''
  try {
    await conversationStore.resetToMessage(props.message.id)
    appStore.userInput = text
    if ((window as any).$message) {
      (window as any).$message.success('消息已重置并退回到输入框')
    }
  } catch (e) {
    console.error('重置到此节点失败:', e)
  }
}

const handleRetry = () => {
  conversationStore.retryMessage(props.message.id)
}

const formatTime = (timeStr?: string) => {
  if (!timeStr) return ''
  try {
    const date = new Date(timeStr)
    if (isNaN(date.getTime())) return timeStr
    
    const pad = (n: number) => String(n).padStart(2, '0')
    const yyyy = date.getFullYear()
    const MM = pad(date.getMonth() + 1)
    const dd = pad(date.getDate())
    const hh = pad(date.getHours())
    const mm = pad(date.getMinutes())
    const ss = pad(date.getSeconds())
    
    return `${yyyy}-${MM}-${dd} ${hh}:${mm}:${ss}`
  } catch {
    return timeStr
  }
}

const cachedTime = ref('')

const displayTime = computed(() => {
  const formatted = formatTime(props.message.createdAt)
  if (formatted) {
    cachedTime.value = formatted
  }
  return cachedTime.value
})
</script>

<style scoped>
.cursor-typing {
  font-size: 0.85rem;
  color: var(--status-warning);
  font-style: italic;
}

.thinking-animation {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 4px;
  margin-top: 4px;
  margin-bottom: 4px;
}

.thinking-dot {
  width: 7px;
  height: 7px;
  background-color: var(--primary-color);
  border-radius: 50%;
  opacity: 0.4;
  animation: thinking-bounce 1.4s infinite both;
}

.thinking-dot:nth-child(1) {
  animation-delay: 0s;
}

.thinking-dot:nth-child(2) {
  animation-delay: 0.2s;
}

.thinking-dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes thinking-bounce {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.35;
  }
  40% {
    transform: scale(1.1);
    opacity: 0.85;
  }
}

.message {
  position: relative;
  transition: all 0.3s ease;
}

.status-pending {
  opacity: 0.65;
  animation: pulse-pending 2s infinite ease-in-out;
}

.status-running {
  /* 移除左侧高亮竖线以保持页面视觉整洁 */
}

.status-failed {
  border: 1px solid #d03050;
  background-color: #2a1215 !important;
}

.status-canceled {
  opacity: 0.55;
  border: 1px dashed #444;
  background-color: #121214 !important;
}

@keyframes pulse-pending {
  0% { opacity: 0.5; }
  50% { opacity: 0.8; }
  100% { opacity: 0.5; }
}

.message-item-wrapper {
  display: flex;
  flex-direction: column;
  width: 100%;
  margin-bottom: 4px;
}

.user-wrapper {
  align-items: flex-end;
}

.assistant-wrapper, .branch-wrapper, .compressed-wrapper {
  align-items: flex-start;
}

.msg-content-wrapper {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 4px;
  flex: 1;
}

.user-message .msg-content-wrapper {
  align-items: flex-end;
}

.assistant-message .msg-content-wrapper, .branch-message .msg-content-wrapper {
  align-items: stretch;
}

.message-action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding: 2px 4px;
  box-sizing: border-box;
  opacity: 0.55;
  transition: opacity 0.2s ease;
  font-size: 0.72rem;
  color: var(--text-color-muted);
}

.message-action-bar:hover {
  opacity: 1;
}

.message-time {
  user-select: none;
  font-family: monospace;
  /* 物理盒子模型高度锁死（与右侧按钮一致），防止 align-items 浮点对齐偏差 */
  height: 24px;
  line-height: 24px;
  display: inline-flex;
  align-items: center;
}

.message-actions-buttons {
  display: flex;
  gap: 6px;
}

.branch-message .avatar {
  background-color: var(--purple-color);
  color: var(--text-color-dark);
}

.compressed-message .avatar {
  background-color: #3b82f6;
  color: #ffffff;
}

.compressed-message .msg-content {
  background-color: var(--bg-color-card);
  border-color: #3b82f6;
}

.compressed-status-text {
  font-weight: 500;
  color: var(--text-color-bright);
}

.branch-message .msg-content {
  background-color: var(--bg-color-card);
  border-color: var(--primary-color);
}

.branch-label {
  color: var(--primary-color);
  font-size: 0.72rem;
  font-weight: 800;
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  display: flex;
  align-items: center;
  gap: 6px;
}

.nested-tool-group-wrapper {
  width: 100%;
}

.msg-content + .nested-tool-group-wrapper {
  margin-top: 12px;
}
</style>
