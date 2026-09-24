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
            <div v-for="(err, idx) in errorTexts" :key="idx" class="warning-item">
              • {{ err }}
            </div>
          </div>
        </n-tooltip>
      </div>
      <n-button class="detail-btn" size="tiny" quaternary type="primary" @click="openDetail">
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
            <!-- 详情加载中 -->
            <div v-if="detailLoading" class="detail-loading">
              <n-spin size="small" />
            </div>
            <!-- 详情加载失败 -->
            <div v-else-if="detailError" class="detail-error">
              {{ detailError }}
            </div>
            <!-- 折叠详情为静态历史区间（已终结的中间步骤），显式传 false 确保不显示末批运行指示 -->
            <message-list-flow
              v-else
              :messages="detailRenderItems"
              :is-sub-agent="true"
              :cid="cid"
              :running="false"
            />
          </div>
        </div>
      </n-card>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, defineAsyncComponent, watch, type CSSProperties } from 'vue'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'
import { formatToolName } from '@/utils/toolName'
import { buildRenderItems, type RenderItem } from '@/utils/foldEngine'
import { getConversationMessages } from '@/api/conversation'
import { Message, FoldErrorDetail } from '@/types'

const props = defineProps<{
  /** 折叠块虚拟消息（携带 foldedMinId/foldedMaxId/assistantCount/toolCount/errorDetails 元信息） */
  folded: Message
  cid?: number | null
}>()

// 使用异步组件导入，彻底避免 Vue 组件循环引用加载死锁 (MessageListFlow <-> FoldedMessageCard)
const MessageListFlow = defineAsyncComponent(() => import('./MessageListFlow.vue'))
const { isMobile } = useResponsive()
const showDetail = ref(false)

// 详情明细状态：按需范围查询加载（folded=false + minId/maxId），关闭弹窗即丢弃，防止内存积压
const detailLoading = ref(false)
const detailError = ref('')
const detailMessages = ref<Message[]>([])
const detailRenderItems = computed<RenderItem[]>(() => buildRenderItems(detailMessages.value))

const assistantCount = computed(() => props.folded.assistantCount || 0)
const toolCount = computed(() => props.folded.toolCount || 0)

// 异常文案映射：kind+toolName 结构化条目 → i18n 文案（与后端约定，后端不拼死文案）
const errorTexts = computed<string[]>(() => {
  const details: FoldErrorDetail[] = props.folded.errorDetails || []
  const texts: string[] = []
  for (const d of details) {
    if (d.kind === 'tool') {
      texts.push(t('chat.foldedFailedTool', { name: formatToolName(d.toolName || 'tool') }))
    } else {
      texts.push(t('chat.foldedFailedMsg'))
    }
  }
  return texts
})

const hasError = computed(() => errorTexts.value.length > 0)

// 打开详情弹窗：携带折叠区间范围查询完整明细（不折叠平铺返回）
const openDetail = async () => {
  showDetail.value = true
  const minId = props.folded.foldedMinId
  const maxId = props.folded.foldedMaxId
  if (minId === undefined || maxId === undefined) {
    detailError.value = t('chat.foldedDetailMissingRange')
    return
  }
  if (!props.cid) {
    detailError.value = t('chat.foldedDetailMissingRange')
    return
  }
  detailLoading.value = true
  detailError.value = ''
  try {
    const res = await getConversationMessages(props.cid, { folded: false, minId, maxId })
    detailMessages.value = (res.messages || []).map(msg => {
      if (msg.role) msg.role = msg.role.toLowerCase() as any
      return msg
    })
  } catch (e) {
    console.error('加载折叠详情消息失败:', e)
    detailError.value = t('chat.foldedDetailLoadError')
  } finally {
    detailLoading.value = false
  }
}

// 关闭弹窗时丢弃明细，释放内存
watch(showDetail, (val) => {
  if (!val) {
    detailMessages.value = []
  }
})

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
  /* 宽度收缩行为交给父级 .folded-wrapper 的 flex 对齐（与助手消息 .message 同机制），此处不再显式声明 fit-content */
  /* 上限统一消费全局变量 --msg-max-width（桌面 85% / 移动端媒体查询放宽到 96%），
     与助手气泡 (.message) 单一来源对齐，杜绝两端值各自为政导致右缘错位、摘要被提前挤换行 */
  max-width: var(--msg-max-width, 85%);
  margin: 6px 0;
  box-sizing: border-box;
}

.folded-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: var(--bg-color-card);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  box-shadow: var(--shadow-card);
  /* 左右 12px 与助手气泡横向留白对齐；fit-content 收缩下 space-between 本无多余空间可分，16px 的 gap 属纯浪费，收紧到 8px 避免窄屏把摘要文字顶到临界换行 */
  padding: 8px 12px;
  box-sizing: border-box;
  transition: border-color 0.25s, background-color 0.25s;
  gap: 8px;
}

.folded-content:hover {
  background-color: var(--primary-bg-weak);
  border-color: var(--primary-color-hover);
}

.info-side {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.85rem;
  /* 折叠条摘要文字刻意用 muted 弱化色：仅是统计性提示，不与正文消息抢视觉层级 */
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
  color: var(--status-error);
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
  color: var(--status-error);
  margin-bottom: 4px;
}

.folded-warning-tooltip .warning-item {
  color: var(--text-color);
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
  background-color: var(--bg-color-modal) !important;
  /* 卡片默认文字色刻意用正文色而非高亮色：弹窗内复用的助手消息正文（.msg-content 无显式 color，就近继承）
     若继承到高亮白会明显亮过主会话里的同一批消息（主会话继承 body 的正文色）。
     标题/副标题等需要更高层级的元素均已各自显式声明颜色，不受此默认值影响 */
  color: var(--text-color) !important;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-color) !important;
  box-shadow: var(--shadow-overlay) !important;
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

/* 详情加载中 / 失败占位 */
.detail-loading,
.detail-error {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-color-muted);
  font-size: 0.85rem;
}

.detail-error {
  color: var(--status-error, var(--status-error));
}

/* 确保详情里的 virtual list 被限制在容器内滚动 */
:deep(.message-flow-list) {
  height: 100%;
  overflow-y: auto;
}
</style>
