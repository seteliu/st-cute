<template>
  <n-drawer
    v-model:show="appStore.showLogDrawer"
    :width="width"
    placement="right"
    style="border-left: 1px solid var(--border-color); box-shadow: var(--shadow-overlay);"
    @after-enter="stickToBottom"
  >
    <n-drawer-content :title="t('logDrawer.title')" closable>
      <div style="display: flex; flex-direction: column; gap: 12px; height: 100%;">

        <!-- 工具名称：黑框外独立标题，置于抽屉内容最顶部 -->
        <div v-if="toolMessage" class="log-section-title">
          {{ t('chat.toolNameLabel') }}: {{ formatToolName(toolMessage.toolName) }}
        </div>

        <!-- 调用参数：标题在黑框外，内容黑框与执行结果统一样式 -->
        <template v-if="toolMessage">
          <div class="log-section-title">{{ t('chat.argumentsLabel') }}:</div>
          <pre class="log-pre log-pre-args">{{ formattedToolCall }}</pre>
        </template>

        <!-- 执行结果展示 -->
        <div style="flex: 1; display: flex; flex-direction: column; min-height: 0; gap: 12px;">
          <div class="log-section-title">{{ t('chat.resultLabel') }}:</div>
          <!-- 执行中且尚无任何输出时才展示占位提示：一旦日志流有内容即展示内容本身，避免实时日志被占位挡住 -->
          <pre v-if="showExecutingPlaceholder" class="log-pre log-pre-main log-pre-executing">{{ t('chat.toolRunningPlaceholder') }}</pre>
          <pre
            v-else
            ref="resultBoxRef"
            class="log-pre log-pre-main"
            @scroll="handleResultScroll"
            >{{ resultDisplay }}</pre>
        </div>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useAgentStore } from '@/stores/agent'
import { useResponsive } from '@/utils/useResponsive'
import { t } from '@/i18n'
import { formatToolName as sharedFormatToolName } from '@/utils/toolName'

const { isMobile } = useResponsive()

const width = computed(() => {
  return isMobile.value ? '100%' : 650
})

const appStore = useAppStore()
const conversationStore = useConversationStore()
const agentStore = useAgentStore()

const resultBoxRef = ref<HTMLElement | null>(null)

// 用户是否停留在底部附近：由 @scroll 实时维护。内容增高不会触发 scroll 事件，
// 因此该值天然表达"内容增长前是否贴底"，作为日志流跟随的判定依据
const isUserNearBottom = ref(true)

// 距底判定：距底 10px 容差内视为停留在底部
const isNearBottom = () => {
  const el = resultBoxRef.value
  if (!el) return false
  return el.scrollHeight - el.scrollTop - el.clientHeight <= 10
}

// 强制贴底并同步记录状态
const stickToBottom = () => {
  const el = resultBoxRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
  isUserNearBottom.value = true
}

// 滚动事件：实时记录用户是否停留在底部附近（向上翻阅时置 false，翻回底部自动恢复跟随）
const handleResultScroll = () => {
  isUserNearBottom.value = isNearBottom()
}

/**
 * 当前查看的工具消息：按 ID 从响应式消息列表实时解析。
 * <p>
 * 主会话消息优先；工具详情由工具卡片点击触发，位于子代理场景时其消息必在当前激活的子代理列表中
 * （与 ThoughtDetailDrawer 取数口径一致）。以 computed 实时取而非点击时快照，
 * 工具执行中即可跟随日志流自动刷新，无需重新打开抽屉。
 * </p>
 */
const toolMessage = computed(() => {
  const targetId = appStore.currentViewToolMessageId
  if (!targetId) return null
  const mainMsg = conversationStore.messages.find(m => m.id === targetId)
  if (mainMsg) return mainMsg
  const subAgent = agentStore.activeSubAgent
  if (subAgent && subAgent.messages) {
    const subMsg = subAgent.messages.find((m: any) => m.id === targetId)
    if (subMsg) return subMsg
  }
  // 兜底：消息不在任何响应式列表时使用调用方传入的对象（如折叠详情弹窗的范围查询结果）
  const fallback = appStore.currentViewToolFallback
  if (fallback && fallback.id === targetId) {
    return fallback
  }
  return null
})

// 执行中/等待审批阶段（含尚无内容）
const isExecuting = computed(() => {
  const status = toolMessage.value?.status
  return status === 'RUNNING' || status === 'PENDING' || status === 'WAITING_APPROVAL'
})

const formatToolName = (name?: string) => {
  return sharedFormatToolName(name)
}

/**
 * 清洗终端 ANSI 控制序列（颜色、光标移动等，如 \u001b[36m），仅用于展示层
 */
const stripAnsi = (text: string): string => {
  return text.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, '')
}

/**
 * 将美化后 JSON 中残留的字面转义序列（\r\n、\n、\t）还原为真实换行/制表符，仅用于展示层提升可读性。
 * 使用负向后行断言避免误伤「转义反斜杠 + n」这类合法序列（如 Windows 路径 P:\\note）。
 */
const unescapePrettyJson = (text: string): string => {
  return text
    .replace(/(?<!\\)\\r\\n/g, '\n')
    .replace(/(?<!\\)\\n/g, '\n')
    .replace(/(?<!\\)\\t/g, '\t')
}

/**
 * 执行结果通用展示格式化：
 * 1. 清洗 ANSI 控制序列；
 * 2. 内容为合法 JSON（对象/数组）时展开为缩进美化格式，并还原值内的换行转义；
 * 3. 解析失败时按纯文本原样展示。
 */
const formatDisplayText = (raw?: string | null): string => {
  if (!raw) return ''
  const cleaned = stripAnsi(raw)
  const trimmed = cleaned.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return unescapePrettyJson(JSON.stringify(JSON.parse(trimmed), null, 2))
    } catch (e) {
      // 非法 JSON，按纯文本展示
    }
  }
  return cleaned
}

// 调用参数完整展示：组合 toolId、toolName、toolArguments 三个字段。
// toolArguments 解析为对象后放入 payload，外层序列化自然嵌套展开，
// 避免字符串字段换行被二次转义为 \n 字面噪音
const formattedToolCall = computed(() => {
  const msg = toolMessage.value
  if (!msg) return '{}'
  let argsObj: unknown = {}
  const rawArgs = msg.toolArguments
  if (rawArgs) {
    if (typeof rawArgs === 'object') {
      argsObj = rawArgs
    } else {
      try {
        argsObj = JSON.parse(rawArgs)
      } catch (e) {
        // 非法 JSON 保持字符串原样
        argsObj = String(rawArgs)
      }
    }
  }
  const payload = {
    toolId: msg.toolId || '',
    toolName: msg.toolName || '',
    toolArguments: argsObj
  }
  return unescapePrettyJson(JSON.stringify(payload, null, 2))
})

// 执行结果展示文本（未截断场景）
const resultDisplay = computed(() => {
  const msg = toolMessage.value
  if (!msg) return ''
  return formatDisplayText(msg.content)
})

// 占位提示展示条件：仍在执行阶段「且」尚无任何输出。
// 一旦日志流产生内容即让位给内容本身，否则实时日志会被占位提示完全挡住
const showExecutingPlaceholder = computed(() => {
  return isExecuting.value && !resultDisplay.value
})

// 打开抽屉时初始化置底：nextTick 快速贴底；入场动画/内容挂载期间布局可能未定型导致单次贴底落空，
// 因此在 n-drawer 的 after-enter（入场动画结束、布局稳定）再兜底校准一次
watch(
  () => appStore.showLogDrawer,
  (show) => {
    if (show) {
      isUserNearBottom.value = true
      nextTick(() => {
        stickToBottom()
      })
    } else {
      // 关闭抽屉时清空兜底对象，避免其长期持有折叠详情等局部数据（内存不膨胀）
      appStore.currentViewToolFallback = null
    }
  }
)

// 日志流式接收时自动跟随置底：依据"内容增长前"的贴底记录判定，避免内容已长高导致实时判定误判为用户上翻而永不跟随；
// 用户向上阅读（scroll 记录为 false）则不强行打扰
watch(
  () => resultDisplay.value,
  () => {
    if (appStore.showLogDrawer && isUserNearBottom.value) {
      nextTick(() => {
        stickToBottom()
      })
    }
  }
)
</script>

<style scoped>
/* 区块标题统一样式：浅紫色加粗，位于黑框外 */
.log-section-title {
  font-weight: bold;
  color: var(--accent-color);
  font-size: 0.95rem;
  font-family: monospace;
  padding: 0 2px;
}

/* 内容黑框统一样式：深底 + 细边框，等宽字体 */
.log-pre {
  background: var(--bg-color);
  color: var(--text-color);
  padding: 12px 15px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  font-family: monospace;
  font-size: 0.8rem;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 调用参数黑框：固定最大高度，超出滚动 */
.log-pre-args {
  max-height: 180px;
  flex-shrink: 0;
}

/* 执行结果主黑框：撑满剩余空间 */
.log-pre-main {
  flex: 1;
  min-height: 0;
}

/* 执行中占位提示：弱化色彩并居中，明确告知"结果尚未产生" */
.log-pre-executing {
  color: var(--accent-color);
  font-style: italic;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
