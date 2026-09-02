<template>
  <div class="tool-calls-container">
    <div
      v-for="tc in tools"
      :key="tc.id"
      class="tool-call-item"
    >
      <div class="tool-call-row">
        <!-- 1. 状态圆点 -->
        <span :class="['tool-status-dot', getStatusClass(tc.status)]"></span>

        <!-- 2. 工具名称 -->
        <span class="tool-name">{{ formatToolName(tc.toolName) }}</span>

        <!-- 3. 工具参数精简提示 -->
        <n-tooltip trigger="hover" placement="top-start">
          <template #trigger>
            <span class="tool-args" style="cursor: help;">{{ formatToolArgs(tc.toolArguments) }}</span>
          </template>
          <div style="font-family: monospace; white-space: pre-wrap; word-break: break-all; max-width: 400px; font-size: 0.75rem;">
            {{ getFullArgsText(tc.toolArguments) }}
          </div>
        </n-tooltip>

        <!-- 4. 状态标签 -->
        <span v-if="tc.status === 'WAITING_APPROVAL'" class="status-label waiting-approval">[{{ t('chat.permissionRequestTitle') }}]</span>
        <span v-else-if="tc.status === 'REJECTED'" class="status-label rejected">[{{ t('common.failed') }}]</span>
        <span v-else-if="tc.status === 'CANCELED'" class="status-label canceled">[{{ t('chat.cancelLoop') }}]</span>
        <span v-else-if="tc.status === 'RUNNING' || tc.status === 'PENDING'" class="tool-status-text">{{ t('common.loading') }}</span>
        <span v-else-if="tc.status === 'SUCCESS' && tc.content" class="tool-result-summary">› {{ getResultSummary(tc.content) }}</span>

        <!-- 5. 查看日志按钮 -->
        <n-button
          v-if="tc.status === 'SUCCESS' || tc.status === 'FAILED'"
          size="tiny"
          quaternary
          type="primary"
          style="margin-left: auto; font-size: 0.75rem;"
          @click="appStore.showRawLog(tc.id)"
        >
          {{ t('chat.viewRawLog') }}
        </n-button>
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

const props = defineProps<{
  parentMessageId: number | string
  tools: Message[]
  cid?: number | null
}>()

const appStore = useAppStore()
const conversationStore = useConversationStore()

const formatToolName = (name: string | undefined) => {
  return sharedFormatToolName(name)
}

const getStatusClass = (status: string | undefined) => {
  if (!status) return 'success'
  const lower = status.toLowerCase()
  if (lower === 'waiting_approval') return 'waiting-approval'
  if (lower === 'rejected') return 'rejected'
  if (lower === 'canceled') return 'canceled'
  if (lower === 'running' || lower === 'pending') return 'running'
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
      return `(cmd="${parsed.command}")`
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

const getFullArgsText = (argsStr: string | undefined) => {
  if (!argsStr) return '{}'
  try {
    const parsed = JSON.parse(argsStr)
    return JSON.stringify(parsed, null, 2)
  } catch (e) {
    return argsStr
  }
}

const getResultSummary = (content: string | undefined) => {
  if (!content) return ''
  const clean = content.trim().replace(/\n/g, ' ')
  return clean.length > 50 ? clean.substring(0, 47) + '...' : clean
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
  appStore.showPermissionModal = false
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
  const isFile = tc.toolName === 'write_to_file' || tc.toolName === 'replace_file_content' || tc.toolName === 'WriteFileTool' || tc.toolName === 'ModifyFileTool'

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
  /* 工具名是行内主标识，禁止换行且不参与 flex 收缩，宽度不足时由参数提示出省略号 */
  white-space: nowrap;
  flex-shrink: 0;
}

.tool-args {
  color: var(--text-color-secondary);
  font-family: monospace;
  font-size: 0.75rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 250px;
  /* 允许自身收缩，宽度不足时优先压缩参数提示（出省略号）而非挤压工具名 */
  min-width: 0;
}

.tool-status-text {
  color: var(--status-warning);
  font-size: 0.75rem;
  font-style: italic;
  margin-left: 8px;
  /* 状态词（如“加载中”）为短文本，禁止换行 */
  white-space: nowrap;
}

.status-label {
  font-size: 0.75rem;
  margin-left: 8px;
  font-weight: bold;
  /* 状态标签（如“[等待审批]”）为短文本，禁止换行 */
  white-space: nowrap;
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

.tool-result-summary {
  color: #a0a0a5;
  font-size: 0.75rem;
  margin-left: 8px;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

@media (max-width: 768px) {
  .tool-args {
    max-width: 90px !important;
  }
  .tool-result-summary {
    max-width: 100px !important;
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
