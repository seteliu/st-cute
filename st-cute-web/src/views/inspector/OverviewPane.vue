<template>
  <div class="pane-content">
    <h4>{{ t('overview.envTitle') }}</h4>
    <div class="info-card" style="padding: 12px; margin-bottom: 16px; background: var(--overlay-bg-glassy); border: 1px solid var(--overlay-veil-strong); border-radius: 8px; box-shadow: var(--shadow-card);">
      <div style="display: flex; flex-direction: column; gap: 12px;">
        <div style="display: flex; justify-content: space-between; align-items: center; gap: 8px;">
          <span style="color: var(--text-color); font-size: 0.85rem; white-space: nowrap; flex-shrink: 0;">{{ t('overview.currentProject') }}</span>
          <n-ellipsis style="min-width: 0; color: var(--text-color-bright); font-weight: bold; font-size: 0.85rem; text-align: right;">
            {{ currentProject ? currentProject.name : t('overview.unselected') }}
          </n-ellipsis>
        </div>
        <div style="display: flex; flex-direction: column; gap: 4px; border-top: 1px solid var(--overlay-veil); padding-top: 8px;">
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <span style="color: var(--text-color); font-size: 0.85rem; white-space: nowrap; flex-shrink: 0;">{{ t('overview.physicalPath') }}</span>
            <n-button
              v-if="currentProject?.path"
              quaternary
              circle
              size="tiny"
              :title="t('overview.copyPath')"
              @click="handleCopyPath(currentProject.path)"
            >
              <template #icon>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                  <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                </svg>
              </template>
            </n-button>
          </div>
          <span style="word-break: break-all; font-family: monospace; font-size: 0.75rem; color: var(--text-color-muted); background: var(--overlay-veil); padding: 4px 6px; border-radius: 4px; border: 1px solid var(--overlay-veil);">
            {{ currentProject ? currentProject.path : t('inspector.none') }}
          </span>
        </div>
        <div style="display: flex; flex-direction: column; gap: 4px; border-top: 1px solid var(--overlay-veil); padding-top: 8px;">
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <span style="color: var(--text-color); font-size: 0.85rem; white-space: nowrap; flex-shrink: 0;">{{ t('overview.gitBranch') }}</span>
            <n-button
              v-if="gitStore.selectedBranch?.branch"
              quaternary
              circle
              size="tiny"
              :title="t('overview.copyBranch')"
              @click="handleCopyBranch(gitStore.selectedBranch.branch)"
            >
              <template #icon>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                  <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                </svg>
              </template>
            </n-button>
          </div>
          <span style="word-break: break-all; font-family: monospace; font-size: 0.75rem; color: var(--text-color-muted); background: var(--overlay-veil); padding: 4px 6px; border-radius: 4px; border: 1px solid var(--overlay-veil);">
            {{ gitStore.selectedBranch?.branch || t('inspector.none') }}
          </span>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid var(--overlay-veil); padding-top: 8px;">
          <span style="color: var(--text-color); font-size: 0.85rem; white-space: nowrap; flex-shrink: 0;">{{ t('overview.activeProcess') }}</span>
          <n-button
            secondary
            strong
            type="info"
            size="tiny"
            style="flex-shrink: 0;"
            @click="showProcessModal = true"
          >
            {{ t('overview.viewDetails') }}
          </n-button>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid var(--overlay-veil); padding-top: 8px;">
          <span style="color: var(--text-color); font-size: 0.85rem; white-space: nowrap; flex-shrink: 0;">{{ t('overview.activeNetwork') }}</span>
          <n-button
            secondary
            strong
            type="info"
            size="tiny"
            style="flex-shrink: 0;"
            @click="showLlmModal = true"
          >
            {{ t('overview.viewDetails') }}
          </n-button>
        </div>
      </div>
    </div>

    <h4>{{ t('overview.subAgentsTitle') }}</h4>
    <div
      v-if="filteredSubAgents.length === 0"
      style="color: var(--text-color-muted); font-style: italic; font-size: 13px; padding: 16px; background: var(--overlay-bg-glassy); border: 1px solid var(--overlay-veil-strong); border-radius: 8px; text-align: center;"
    >
      {{ t('overview.noSubAgents') }}
    </div>
    <div
      v-else
      v-for="sub in filteredSubAgents"
      :key="sub.cid"
      :class="['subagent-card', sub.status?.toLowerCase()]"
      @click="agentStore.openSubAgentDrawer(Number(sub.cid))"
    >
      <div class="subagent-header" style="display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 8px;">
        <n-ellipsis style="min-width: 0; flex: 1;">
          <span class="role" style="font-weight: bold; font-size: 0.85rem; color: var(--text-color-bright);">{{ sub.role }}</span>
        </n-ellipsis>
        <sub-agent-status-tag :status="sub.status" style="flex-shrink: 0;" />
      </div>
      <div class="subagent-task">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
          <span style="color: var(--accent-color); font-family: monospace; font-weight: bold;">[ID: {{ sub.cid }}]</span>
        </div>

        <div style="font-size: 11px; margin-top: 4px; color: var(--text-color); display: flex; align-items: center; justify-content: space-between;">
          <token-metrics-tooltip
            :input-tokens="sub.inputTokens || 0"
            :output-tokens="sub.outputTokens || 0"
            :cached-tokens="sub.cachedTokens"
            :cid="sub.cid ? Number(sub.cid) : undefined"
            :context-limit="contextLimit"
          >
            <template #default="{ total }">
              上下文窗口: <strong style="color: var(--text-color-bright); font-weight: bold;">{{ formatTokenCount(total) }}</strong><template v-if="contextLimitText"> / {{ contextLimitText }} ({{ usagePercentage(total) }}%)</template>
            </template>
          </token-metrics-tooltip>
        </div>
      </div>

      <!-- 删除按钮 -->
      <n-popconfirm
        v-if="sub.status === 'success'"
        @positive-click="agentStore.deleteSubAgent(Number(sub.cid))"
        placement="bottom-end"
        :positive-text="t('common.confirm')"
        :negative-text="t('common.cancel')"
      >
        <template #trigger>
          <span
            class="delete-subagent-btn"
            @click.stop
          >
            ✕
          </span>
        </template>
        {{ t('sider.deleteConfirmContent') }}
      </n-popconfirm>
    </div>

    <!-- 活动子进程详情控制弹窗 -->
    <n-modal
      v-model:show="showProcessModal"
      preset="card"
      title="活动子进程看板"
      style="width: 620px; max-width: 92vw; border: 1px solid var(--border-color); box-shadow: var(--shadow-overlay);"
    >
      <div>
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
          <span style="font-size: 12px; color: var(--text-color-muted);">
            共检测到 <strong style="color: var(--accent-color);">{{ activeProcessesList.length }}</strong> 个正在后台同步运行的物理进程（包含并行子代理拉起的进程）
          </span>
          <n-button
            v-if="activeProcessesList.length > 0"
            type="error"
            size="small"
            secondary
            @click="handleKillProcess()"
          >
            全部强杀 (Kill All)
          </n-button>
        </div>

        <div v-if="activeProcessesList.length === 0" style="padding: 24px; text-align: center; color: var(--text-color-faint); font-style: italic; background: var(--overlay-veil); border-radius: 6px;">
          暂无活动子进程，当智能体执行编译、运行测试等命令工具时在此展示。
        </div>

        <div v-else style="max-height: 400px; overflow-y: auto; display: flex; flex-direction: column; gap: 12px;">
          <div
            v-for="proc in activeProcessesList"
            :key="proc.toolCallId"
            style="padding: 12px; background: var(--overlay-veil); border: 1px solid var(--border-color); border-radius: 6px; display: flex; flex-direction: column; gap: 6px;"
          >
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold; font-family: monospace; color: var(--accent-color);">PID: {{ proc.pid }}</span>
              <div style="display: flex; align-items: center; gap: 8px;">
                <n-tag size="mini" round :bordered="false" style="color: var(--accent-color); background-color: var(--accent-bg-weak);">
                  {{ proc.sessionTitle }}
                </n-tag>
                <n-button
                  type="error"
                  size="tiny"
                  quaternary
                  @click="handleKillProcess(proc.toolCallId)"
                >
                  强杀
                </n-button>
              </div>
            </div>
            
            <div style="font-size: 11px; color: var(--text-color-muted); word-break: break-all; background: var(--bg-color-code); padding: 4px 6px; border-radius: 4px; font-family: monospace;">
              {{ proc.command }}
            </div>

            <div style="display: flex; justify-content: space-between; align-items: center; font-size: 11px; color: var(--text-color-faint);">
              <span>工作目录: {{ proc.cwd }}</span>
              <span>运行时间: <span style="color: var(--accent-color);">{{ formatDuration(proc.runningTimeMs) }}</span></span>
            </div>
          </div>
        </div>
      </div>
    </n-modal>

    <!-- 活动大模型连接详情控制弹窗 -->
    <n-modal
      v-model:show="showLlmModal"
      preset="card"
      title="活动 LLM 网络请求"
      style="width: 580px; max-width: 92vw; border: 1px solid var(--border-color); box-shadow: var(--shadow-overlay);"
    >
      <div>
        <div style="margin-bottom: 12px;">
          <span style="font-size: 12px; color: var(--text-color-muted);">
            共检测到 <strong style="color: var(--accent-color);">{{ activeLlmCallsList.length }}</strong> 个正在进行的 LLM HTTP 网络请求
          </span>
        </div>

        <div v-if="activeLlmCallsList.length === 0" style="padding: 24px; text-align: center; color: var(--text-color-faint); font-style: italic; background: var(--overlay-veil); border-radius: 6px;">
          暂无活动 LLM 请求，当智能体发起大模型调用或进行会话压缩/重命名时在此展示。
        </div>

        <div v-else style="max-height: 400px; overflow-y: auto; display: flex; flex-direction: column; gap: 10px;">
          <div
            v-for="call in activeLlmCallsList"
            :key="call.llmCallId"
            style="padding: 12px; background: var(--overlay-veil); border: 1px solid var(--border-color); border-radius: 6px; display: flex; flex-direction: column; gap: 6px;"
          >
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold; font-family: monospace; color: var(--accent-color); font-size: 11px;">
                ID: {{ call.llmCallId }}
              </span>
              <n-tag size="mini" round :bordered="false" style="color: var(--accent-color); background-color: var(--accent-bg-weak);">
                {{ call.sessionTitle }}
              </n-tag>
            </div>
            
            <div style="display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--text-color-bright);">
              <span>模型: <strong style="font-family: monospace; color: var(--accent-color);">{{ call.model }}</strong></span>
              <span style="color: var(--text-color-faint);">耗时: <span style="color: var(--accent-color);">{{ formatDuration(call.durationTimeMs) }}</span></span>
            </div>
          </div>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { useMessage } from 'naive-ui'
import { copyTextToClipboard } from '@/utils/clipboard'
import { t } from '@/i18n'
import { useAgentStore } from '@/stores/agent'
import { useProjectStore } from '@/stores/project'
import { useConversationStore } from '@/stores/conversation'
import { useGitStore } from '@/stores/git'
import TokenMetricsTooltip from '@/components/TokenMetricsTooltip.vue'
import SubAgentStatusTag from '@/components/SubAgentStatusTag.vue'
import { useContextWindow, formatTokenCount } from '@/composables/useContextWindow'
import { getConversationProcessesApi, killConversationProcessApi, getConversationLlmCallsApi } from '@/api/conversation'
import type { ActiveProcessInfo, ActiveLlmCallInfo } from '@/types'

const agentStore = useAgentStore()
const projectStore = useProjectStore()
const conversationStore = useConversationStore()
const gitStore = useGitStore()
const message = useMessage()

// 上下文窗口口径：走共享 composable，恒取主会话绑定供应商的窗口大小（子会话继承父绑定）
const { contextLimit, contextLimitText, usagePercentage } = useContextWindow()

// 活动子进程监控与强杀状态
const showProcessModal = ref(false)
const activeProcessesList = ref<ActiveProcessInfo[]>([])

// 活动大模型网络连接请求状态
const showLlmModal = ref(false)
const activeLlmCallsList = ref<ActiveLlmCallInfo[]>([])

let refreshTimer: any = null
let llmRefreshTimer: any = null

const loadActiveProcesses = async () => {
  const cid = conversationStore.activeCid
  if (!cid) {
    activeProcessesList.value = []
    return
  }
  try {
    const res = await getConversationProcessesApi(cid)
    activeProcessesList.value = res || []
  } catch (e) {
    console.error('获取活动子进程失败:', e)
  }
}

const startPollingProcesses = () => {
  stopPollingProcesses()
  loadActiveProcesses()
  refreshTimer = window.setInterval(() => {
    loadActiveProcesses()
  }, 1000)
}

const stopPollingProcesses = () => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
}

const loadActiveLlmCalls = async () => {
  const cid = conversationStore.activeCid
  if (!cid) {
    activeLlmCallsList.value = []
    return
  }
  try {
    const res = await getConversationLlmCallsApi(cid)
    activeLlmCallsList.value = res || []
  } catch (e) {
    console.error('获取活动LLM连接失败:', e)
  }
}

const startPollingLlmCalls = () => {
  stopPollingLlmCalls()
  loadActiveLlmCalls()
  llmRefreshTimer = window.setInterval(() => {
    loadActiveLlmCalls()
  }, 1000)
}

const stopPollingLlmCalls = () => {
  if (llmRefreshTimer) {
    window.clearInterval(llmRefreshTimer)
    llmRefreshTimer = null
  }
}

const handleKillProcess = async (toolCallId?: string) => {
  const cid = conversationStore.activeCid
  if (!cid) return
  try {
    await killConversationProcessApi(cid, toolCallId)
    message.success(toolCallId ? '子进程强杀指令已发送' : '所有子进程强杀指令已发送')
    loadActiveProcesses()
  } catch (e) {
    message.error('子进程强杀指令发送失败，请重试')
  }
}

const formatDuration = (ms: number) => {
  if (!ms) return '0s'
  const totalSec = Math.floor(ms / 1000)
  const minutes = Math.floor(totalSec / 60)
  const seconds = totalSec % 60
  if (minutes > 0) {
    return `${minutes}分${seconds}秒`
  }
  return `${seconds}秒`
}

watch(showProcessModal, (visible) => {
  if (visible) {
    startPollingProcesses()
  } else {
    stopPollingProcesses()
  }
})

watch(showLlmModal, (visible) => {
  if (visible) {
    startPollingLlmCalls()
  } else {
    stopPollingLlmCalls()
  }
})

onUnmounted(() => {
  stopPollingProcesses()
  stopPollingLlmCalls()
})

const handleCopyPath = async (path: string) => {
  if (!path) return
  const ok = await copyTextToClipboard(path)
  if (ok) {
    message.success('项目物理路径已复制到剪贴板')
  } else {
    message.error('物理路径复制失败：当前环境不支持自动复制')
  }
}

const handleCopyBranch = async (branch: string) => {
  if (!branch) return
  const ok = await copyTextToClipboard(branch)
  if (ok) {
    message.success('Git 分支名已复制到剪贴板')
  } else {
    message.error('Git 分支名复制失败：当前环境不支持自动复制')
  }
}

const currentProject = computed(() => {
  const activeId = projectStore.activeProjectId
  return projectStore.projectList.find(p => p.id === activeId)
})

const filteredSubAgents = computed(() => {
  const activeCid = conversationStore.activeCid
  if (!activeCid) return []
  return agentStore.subAgents.filter(
    (sub: any) => String(sub.parentCid) === String(activeCid)
  )
})
</script>

<style scoped>
.pulse-red-dot {
  width: 6px;
  height: 6px;
  background-color: var(--status-error);
  border-radius: 50%;
  display: inline-block;
  box-shadow: 0 0 0 0 var(--status-error);
  animation: pulse-dot 1.5s infinite;
}

@keyframes pulse-dot {
  0% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 var(--status-error);
  }
  70% {
    transform: scale(1);
    box-shadow: 0 0 0 5px transparent;
  }
  100% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 transparent;
  }
}

.pane-content {
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
  padding: 12px 16px;
}

/* 子智能体卡片：基础与悬浮点击交互向 RulesPane 的 .rule-card 严格对齐 */
.subagent-card {
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 12px;
  cursor: pointer;
  margin-bottom: 12px;
  position: relative;
  transition: all 0.25s cubic-bezier(0.25, 0.8, 0.25, 1);
}

.subagent-card:hover {
  border-color: var(--primary-color);
  background: var(--bg-color-inset);
  transform: translateY(-2px);
  box-shadow: var(--shadow-card);
}

.subagent-card:active {
  transform: translateY(0);
  box-shadow: none;
}

.delete-subagent-btn {
  position: absolute;
  right: 12px;
  bottom: 12px;
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-color-bright);
  font-size: 0.8rem;
  opacity: 0;
  transition: opacity 0.2s, color 0.2s;
  z-index: 10;
}

.subagent-card:hover .delete-subagent-btn {
  opacity: 0.6;
}

.delete-subagent-btn:hover {
  opacity: 1 !important;
  color: var(--status-error);
}
</style>
