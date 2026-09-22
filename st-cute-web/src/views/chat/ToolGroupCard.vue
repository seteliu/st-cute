<template>
  <div class="tool-calls-container">
    <div
      v-for="tc in tools"
      :key="tc.id"
      class="tool-call-item"
    >
      <div class="tool-call-row">
        <!-- 1. 状态圆点（左侧固定块，不参与收缩） -->
        <span :class="['tool-status-dot', getStatusClass(tc.status)]"></span>

        <!-- 2. 工具名称（左侧固定块，不参与收缩） -->
        <span class="tool-name">{{ formatToolName(tc.toolName) }}</span>

        <!-- 3. 中间弹性区：入参优先保障，出参吃掉剩余空间；宽度不足时各自出省略号；出参为空时入参独占整个弹性区 -->
        <div class="tool-io-zone" :class="{ 'has-result': hasResultContent(tc.content) }">
          <!-- 入参精简提示（完整参数不做悬浮展示，全状态可点击右侧"详情"查看抽屉详情） -->
          <span class="tool-args">{{ formatToolArgs(tc.toolArguments) }}</span>
          <!-- 出参摘要：只要结果内容非空即展示（执行中/失败等状态若已有内容同样呈现） -->
          <span v-if="hasResultContent(tc.content)" class="tool-result-summary">› {{ getResultSummary(tc.content) }}</span>
        </div>

        <!-- 4. 状态栏（右侧固定块，含状态标签/执行动效与详情按钮，不参与收缩） -->
        <div class="tool-status-bar">
          <span v-if="tc.status === 'WAITING_APPROVAL'" class="status-label waiting-approval">[{{ t('chat.permissionRequestTitle') }}]</span>
          <span v-else-if="tc.status === 'REJECTED'" class="status-label rejected">[{{ t('common.failed') }}]</span>
          <span v-else-if="tc.status === 'CANCELED'" class="status-label canceled">[{{ t('chat.cancelLoop') }}]</span>

          <!-- 执行中指示：任何工具只要处于执行中/待执行，即在详情按钮左侧显示三点跳动动效。
               此条件刻意与上方状态标签解耦为独立判断（而非挂在 v-else-if 链尾），
               确保「不论何种工具，只要 RUNNING 就有特效」不被标签分支吞掉；
               收口后由 v-if 整块移除，不残留宽度占位，详情按钮自动左移 -->
          <thinking-dots v-if="isToolRunning(tc.status)" />

          <!-- 详情按钮（全状态常显且样式统一：终态查完整日志，执行中/待审批查已落库的入参） -->
          <n-button
            size="tiny"
            quaternary
            type="primary"
            class="tool-detail-btn"
            @click="appStore.showRawLog(tc.id, tc)"
          >
            {{ t('chat.detail') }}
          </n-button>
        </div>
      </div>

      <!-- 6. 就地审批控制面板 -->
      <div v-if="tc.status === 'WAITING_APPROVAL'" class="approval-panel">
        <n-button size="tiny" type="primary" secondary @click="approveTool(tc.toolId, 'ALLOW', tc.toolName)">{{ t('chat.allow') }}</n-button>
        <n-dropdown
          trigger="click"
          :options="getAlwaysAllowOptions(tc)"
          :render-label="renderDropdownLabel"
          :placement="dropdownPlacement"
          :show="activeDropdownId === tc.id"
          :menu-props="() => ({ class: 'approval-dropdown' })"
          @update:show="(show: boolean) => activeDropdownId = show ? tc.id : null"
          @select="(key: string | number) => handleAlwaysAllowSelect(tc, String(key))"
        >
          <n-button size="tiny" type="warning" secondary>{{ t('chat.alwaysAllow') }} ▾</n-button>
        </n-dropdown>
        <n-button size="tiny" type="error" secondary @click="approveTool(tc.toolId, 'DENY', tc.toolName)">{{ t('chat.deny') }}</n-button>
      </div>

      <!-- 7. 绑定的 Hook 状态渲染 -->
      <div
        v-if="tc.hooks && tc.hooks.length > 0"
        class="tool-hooks-container"
      >
        <div
          v-for="hk in tc.hooks"
          :key="hk.name"
          class="tool-hook-row"
        >
          <span
            :class="['tool-hook-dot', hk.status.toLowerCase()]"
          ></span>
          <span class="tool-hook-label">[Hook: {{ hk.name }}]</span>
          <span
            :class="['tool-hook-status', hk.status.toLowerCase()]"
          >{{ hk.status.toUpperCase() }}</span>
          <span
            v-if="hk.error"
            class="tool-hook-error"
            :title="hk.error"
          >
            - {{ hk.error }}
          </span>
        </div>
      </div>
    </div>

    <!-- 工具组为末批且尚未收口时，于卡片内部底部显示三点跳动指示，
         表示"这一批还在跑"，避免在卡片之外孤立成行 -->
    <div v-if="showTailDots" class="tool-waiting-dots">
      <thinking-dots />
    </div>
  </div>
</template>

<script setup lang="ts">
import { h, ref, onMounted, onUnmounted, computed } from 'vue'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { approveConversationPermissionApi } from '@/api/conversation'
import { Message } from '@/types'
import { t } from '@/i18n'
import { formatToolName as sharedFormatToolName } from '@/utils/toolName'
import ThinkingDots from '@/components/ThinkingDots.vue'

const props = defineProps<{
  parentMessageId: number | string
  tools: Message[]
  cid?: number | null
  /**
   * 该批工具是否处于「末批且尚未收口」状态（会话运行中、批次为最后一条消息、且批内工具均已成功）。
   * 判定由父级 MessageListFlow 统一负责，本组件仅负责在卡片内部底部渲染三点跳动指示。
   */
  showTailDots?: boolean
  /**
   * 所属会话是否处于运行中。必传——由调用链上游按会话语义显式表态，刻意不提供内部回退。
   * 作为工具行「执行态视觉」的守卫（状态圆点脉冲、执行中三点）：会话已停时，
   * 残留的 RUNNING/PENDING 工具消息（如进程重启后自愈未覆盖、子智能体被系统重置清理）
   * 不应继续呈现推进中的动效。
   */
  running: boolean
}>()

const appStore = useAppStore()
const conversationStore = useConversationStore()

const formatToolName = (name: string | undefined) => {
  return sharedFormatToolName(name)
}

/**
 * 工具是否处于执行中/待执行（且所属会话确实在运行）。
 * 与 getStatusClass 同口径做大小写归一，确保不论何种工具、状态字段以何种大小写下发，都能命中执行态特效；
 * 叠加会话运行态守卫，避免残留的 RUNNING/PENDING 工具消息导致特效常亮
 */
const isToolRunning = (status: string | undefined) => {
  const lower = (status || '').toLowerCase()
  return (lower === 'running' || lower === 'pending') && props.running
}

/**
 * 状态圆点样式类。运行态由会话运行态守卫：会话已停时，残留的 RUNNING 圆点
 * 不再按 running 类渲染（避免脉冲动效常亮），降级为普通静态圆点
 */
const getStatusClass = (status: string | undefined) => {
  if (!status) return 'success'
  const lower = status.toLowerCase()
  if (lower === 'waiting_approval') return 'waiting-approval'
  if (lower === 'rejected') return 'rejected'
  if (lower === 'canceled') return 'canceled'
  if (lower === 'running' || lower === 'pending') {
    return props.running ? 'running' : 'success'
  }
  // 执行失败：映射为红色圆点，与成功状态明确区分（main.css 已有 .failed 样式）
  if (lower === 'failed') return 'failed'
  return 'success'
}

const formatToolArgs = (argsStr: string | undefined) => {
  if (!argsStr) return ''

  let parsed: any = null
  try {
    parsed = JSON.parse(argsStr)
  } catch (e) {
    return `(${argsStr})`
  }

  if (!parsed) return ''
  try {
    if (parsed.path) {
      return `(path="${parsed.path}")`
    }
    if (parsed.command) {
      return `(command="${parsed.command}")`
    }
    if (parsed.query) {
      return `(query="${parsed.query}")`
    }
    if (parsed.pattern) {
      return `(pattern="${parsed.pattern}")`
    }
    return `(${JSON.stringify(parsed)})`
  } catch (e) {
    return `(${argsStr})`
  }
}

// 出参摘要取值：仅做换行折叠与超长兜底截断（防止超大工具输出撑爆虚拟列表 DOM），
// 真正的"显示不下出省略号"交由容器 CSS text-overflow 处理
const getResultSummary = (content: string | undefined) => {
  if (!content) return ''
  const clean = content.trim().replace(/\n/g, ' ')
  return clean.length > 300 ? clean.substring(0, 300) + '...' : clean
}

// 出参是否具备可展示内容：空白字符串（如仅含换行/空格）不算，避免渲染出孤立的「›」符号
const hasResultContent = (content: string | undefined) => {
  return !!content && content.trim().length > 0
}

// 人在回路就地确认
const approveTool = (toolCallId: string | undefined, decision: 'ALLOW' | 'ALLOW_ALWAYS' | 'DENY', toolName: string | undefined) => {
  if (!toolCallId || !toolName) return
  const cid = props.cid !== undefined && props.cid !== null ? props.cid : conversationStore.activeCid
  if (cid !== null) {
    approveConversationPermissionApi(cid, {
      id: toolCallId,
      decision: decision === 'DENY' ? 'DENY' : 'ALLOW',
      alwaysAllow: decision === 'ALLOW_ALWAYS',
      toolName: toolName,
      contentPattern: '*'
    }).catch(err => {
      console.error('发送就地审批决定失败:', err)
    })
  }
}

const getAlwaysAllowOptions = (tc: any) => {
  const options = []
  let command = ''
  let path = ''
  try {
    const args = JSON.parse(tc.toolArguments || '{}')
    command = args.command || ''
    path = args.path || ''
  } catch (e) {
    // ignore
  }

  const isCmd = tc.toolName === 'execute_command' || tc.toolName === 'RunCommandTool'
  const isFile = tc.toolName === 'write_file' || tc.toolName === 'edit_file' || tc.toolName === 'WriteFileTool' || tc.toolName === 'EditFileTool'

  if (isCmd) {
    if (command) {
      options.push({
        label: `放行精确命令: "${command}"`,
        key: `exact:${command}`
      })
      const trimmed = command.trim()
      const firstWord = trimmed.split(/\s+/)[0]
      if (firstWord && !trimmed.includes('*')) {
        options.push({
          label: `放行此类命令: "${firstWord} *"${firstWord.toLowerCase() === 'rm' ? ' (高危!)' : ''}`,
          key: `prefix:${firstWord} *`
        })
      }
    }
  } else if (isFile) {
    if (path) {
      // 提取文件名
      const fileName = path.split(/[/\\]/).pop() || path
      options.push({
        label: `仅放行此文件: "${fileName}"`,
        key: `exact:${path}`
      })
      // 放行该文件所在的目录
      const lastSlashIdx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
      if (lastSlashIdx !== -1) {
        const parentPath = path.substring(0, lastSlashIdx)
        options.push({
          label: `放行该目录: "${parentPath}/**"`,
          key: `prefix:${parentPath}/**`
        })
      }
    }
  }

  // 兜底选项：放行全部此类工具的调用
  options.push({
    label: `放行 ${formatToolName(tc.toolName) || '此工具'} 所有调用 (请谨慎选择)`,
    key: 'all:*'
  })

  return options
}

const activeDropdownId = ref<number | null>(null)

const handleGlobalScroll = () => {
  if (activeDropdownId.value) {
    activeDropdownId.value = null
  }
}

const isMobile = ref(false)
const checkMobile = () => {
  isMobile.value = window.innerWidth < 768
}
onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
  window.addEventListener('scroll', handleGlobalScroll, true)
})
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
  window.removeEventListener('scroll', handleGlobalScroll, true)
})

const dropdownPlacement = computed(() => isMobile.value ? 'bottom-end' : 'bottom-start')

const renderDropdownLabel = (option: any) => {
  const labelText = option.label as string
  const colonIdx = labelText.indexOf(':')
  if (colonIdx !== -1) {
    const prefix = labelText.substring(0, colonIdx + 1)
    const code = labelText.substring(colonIdx + 1)
    return h(
      'div',
      {
        style: {
          width: 'min(250px, 70vw)',
          whiteSpace: 'normal',
          wordBreak: 'break-all',
          lineHeight: '1.45',
          fontSize: '0.78rem',
          padding: '4px 0'
        }
      },
      [
        h('span', { style: { color: 'rgba(255, 255, 255, 0.8)', marginRight: '4px' } }, prefix),
        h('code', {
          style: {
            fontFamily: 'monospace',
            backgroundColor: 'rgba(0, 0, 0, 0.25)',
            color: '#e3e3e7',
            padding: '2px 6px',
            borderRadius: '4px',
            border: '1px solid rgba(255, 255, 255, 0.05)',
            fontSize: '0.75rem',
            display: 'inline-block',
            marginTop: '2px',
            transition: 'all 0.15s ease',
            maxWidth: '100%',
            boxSizing: 'border-box',
            whiteSpace: 'normal',
            wordBreak: 'break-all'
          }
        }, code)
      ]
    )
  }

  return h(
    'div',
    {
      style: {
        maxWidth: 'min(280px, 80vw)',
        whiteSpace: 'normal',
        wordBreak: 'break-all',
        lineHeight: '1.45',
        fontSize: '0.78rem',
        padding: '4px 0',
        color: 'rgba(255, 255, 255, 0.8)'
      }
    },
    option.label
  )
}

const handleAlwaysAllowSelect = (tc: any, key: string) => {
  const colonIdx = key.indexOf(':')
  if (colonIdx === -1) return
  const mode = key.substring(0, colonIdx)
  const pattern = key.substring(colonIdx + 1)

  const cid = props.cid !== undefined && props.cid !== null ? props.cid : conversationStore.activeCid
  if (cid !== null && tc.toolId && tc.toolName) {
    approveConversationPermissionApi(cid, {
      id: tc.toolId,
      decision: 'ALLOW',
      alwaysAllow: true,
      toolName: tc.toolName,
      contentPattern: pattern
    }).catch(err => {
      console.error('发送总是放行审批决定失败:', err)
    })
  }
}
</script>

<style scoped>
.tool-calls-container {
  background-color: #16161a;
  border: 1px solid #2d2d30;
  border-radius: 6px;
  padding: 8px 12px;
  margin-top: 0;
  margin-bottom: 8px;
  width: 100%;
  box-sizing: border-box;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-call-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 末批工具等待子智能体返回的三点指示：位于卡片容器内部底部、各工具行之下，
   表示"这批工具尚未收口"。高度恒定且仅含 transform/opacity 动画，
   不会扰动虚拟列表 ResizeObserver 的高度测量。
   左内边距为 0：卡片容器自身的 padding-left（12px）已提供缩进，
   三点恰好落在工具行状态圆点的同一列上；此前额外的 20px 会让三点看起来"空了一格"。
   下内边距 4px：三点上方已有父级 gap（8px）加自身 padding-top（2px）共 10px 间距，
   若下方只靠容器 padding-bottom（8px）会显得贴边，补 4px 使上下留白接近 */
.tool-waiting-dots {
  display: flex;
  align-items: center;
  padding-left: 0;
  padding-top: 2px;
  padding-bottom: 4px;
  pointer-events: none;
}

.tool-call-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.tool-status-dot.success {
  background-color: var(--primary-color);
}

.tool-status-dot.running {
  background-color: var(--status-warning);
  animation: pulse 1.5s infinite ease-in-out;
}

.tool-status-dot.waiting-approval {
  background-color: var(--status-warning);
}

.tool-status-dot.rejected {
  background-color: #d03050;
}

.tool-status-dot.canceled {
  background-color: var(--text-color-secondary);
}

.tool-status-dot.failed {
  background-color: var(--status-error);
  box-shadow: 0 0 6px rgba(208, 48, 80, 0.5);
}

@keyframes pulse {
  0% { transform: scale(0.95); opacity: 0.5; }
  50% { transform: scale(1.05); opacity: 1; }
  100% { transform: scale(0.95); opacity: 0.5; }
}

.tool-name {
  font-family: monospace;
  font-weight: bold;
  color: #e3e3e7;
  font-size: 0.85rem;
  /* 工具名是行内主标识（左侧固定块），禁止换行且不参与 flex 收缩 */
  white-space: nowrap;
  flex-shrink: 0;
}

/* 中间弹性区：入参优先保障、出参吃剩余空间，自身可收缩到 0；
   左右固定块宽度不足时，优先压缩本区域（内部文本出省略号）而非挤压工具名与状态栏 */
.tool-io-zone {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 入参：优先保障。按内容自然宽度占位（内容短则只占自身宽度），可压缩出省略号。
   flex-basis 用 auto 而非 0，是其「内容决定初始宽度」的关键 */
.tool-args {
  flex: 0 1 auto;
  /* 覆盖全局遗留的 max-width: 250px，否则会把弹性区卡死 */
  max-width: none;
  min-width: 0;
  color: var(--text-color-secondary);
  font-family: monospace;
  font-size: 0.75rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 存在出参时，把入参上限压到 4/5（至少留 1/5 给出参），避免长入参挤没出参 */
.tool-io-zone.has-result .tool-args {
  max-width: 80%;
}

/* 右侧状态栏：状态标签/执行动效 + 详情按钮，整体固定不参与收缩 */
.tool-status-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.status-label {
  font-size: 0.75rem;
  margin-left: 0;
  font-weight: bold;
  /* 状态标签（如“[等待审批]”）为短文本，禁止换行；且为关键状态信息，不参与 flex 收缩 */
  white-space: nowrap;
  flex-shrink: 0;
}

.status-label.waiting-approval {
  color: var(--status-warning);
}

.status-label.rejected {
  color: #d03050;
}

.status-label.canceled {
  color: var(--text-color-secondary);
}

/* 出参：吃掉入参之外的全部剩余空间（入参短则自动变宽），可压缩出省略号 */
.tool-result-summary {
  flex: 1 1 0;
  /* 覆盖全局遗留的 max-width，改由弹性区动态决定可用宽度 */
  max-width: none;
  min-width: 0;
  color: #a0a0a5;
  font-size: 0.75rem;
  margin-left: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 详情按钮：位于右侧状态栏内固定不收缩，宽度恒定避免状态切换时行内重排跳变 */
.tool-detail-btn {
  flex-shrink: 0;
  font-size: 0.75rem;
}

.approval-panel {
  margin-left: 16px;
  margin-top: 4px;
  display: flex;
  gap: 8px;
}

/* Hook 切面渲染样式 */
.tool-hooks-container {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-left: 20px;
  padding: 4px 8px;
  background-color: #101014;
  border-radius: 4px;
  border: 1px dashed #2d2d30;
  width: fit-content;
}

.tool-hook-row {
  font-family: monospace;
  font-size: 0.75rem;
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-hook-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.tool-hook-dot.running {
  background-color: var(--status-warning);
}

.tool-hook-dot.success {
  background-color: var(--primary-color);
}

.tool-hook-dot.failed {
  background-color: #d03050;
}

.tool-hook-label {
  color: var(--text-color-secondary);
}

.tool-hook-status.running {
  color: var(--status-warning);
  font-weight: bold;
}

.tool-hook-status.success {
  color: var(--primary-color);
  font-weight: bold;
}

.tool-hook-status.failed {
  color: #d03050;
  font-weight: bold;
}

.tool-hook-error {
  color: #a0a0a5;
  font-style: italic;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 窄屏降级：空间不足时隐藏出参，仅保留入参独占整个弹性区 */
@media (max-width: 768px) {
  .tool-result-summary {
    display: none;
  }
}
</style>

<style>
/* 自定义审批就地确认下拉菜单全局样式覆盖 */
.n-dropdown-menu {
  background-color: rgba(22, 22, 26, 0.94) !important;
  backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.08) !important;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.6) !important;
  padding: 6px !important;
  border-radius: 10px !important;
}

.n-dropdown-menu .n-dropdown-option {
  margin: 2px 0 !important;
  border-radius: 6px !important;
  height: auto !important;
}

.n-dropdown-menu .n-dropdown-option-body {
  height: auto !important;
  min-height: 36px;
  padding: 6px 12px !important;
}

/* 强行控制下拉选项任何状态（包括 Hover/Pending）下的折行，防止长命令溢出 */
.n-dropdown-menu .n-dropdown-option-body,
.n-dropdown-menu .n-dropdown-option-body__label,
.n-dropdown-menu .n-dropdown-option-body__label code {
  white-space: normal !important;
  word-break: break-all !important;
  max-width: 100% !important;
}

/* 选项悬浮交互高亮 */
.n-dropdown-menu .n-dropdown-option:hover {
  background-color: rgba(240, 160, 32, 0.12) !important;
}

.n-dropdown-menu .n-dropdown-option-content {
  color: #c2c2c9 !important;
  transition: color 0.15s ease;
}

.n-dropdown-menu .n-dropdown-option:hover .n-dropdown-option-content {
  color: #f0a020 !important;
}

/* 选项中的代码块在选项悬浮时的联动变色效果：使用极深背景以确保文字高可读性 */
.n-dropdown-menu .n-dropdown-option:hover code {
  color: #f0a020 !important;
  border-color: rgba(240, 160, 32, 0.35) !important;
  background-color: rgba(0, 0, 0, 0.75) !important; /* 加深背景色 */
}
</style>
