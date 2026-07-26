<template>
  <div class="pane-content">
    <h4>{{ t('overview.envTitle') }}</h4>
    <div class="info-card" style="padding: 16px; margin-bottom: 20px; background: rgba(30, 30, 35, 0.4); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);">
      <div style="display: flex; flex-direction: column; gap: 12px;">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span style="color: var(--text-color-secondary); font-size: 0.85rem;">{{ t('overview.currentProject') }}</span>
          <span style="color: var(--text-color-bright); font-weight: bold; font-size: 0.85rem;">
            {{ currentProject ? currentProject.name : t('overview.unselected') }}
          </span>
        </div>
        <div style="display: flex; flex-direction: column; gap: 4px; border-top: 1px solid rgba(255,255,255,0.04); padding-top: 8px;">
          <div style="display: flex; justify-content: space-between; align-items: center;">
            <span style="color: var(--text-color-secondary); font-size: 0.85rem;">{{ t('overview.physicalPath') }}</span>
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
          <span style="word-break: break-all; font-family: monospace; font-size: 0.75rem; color: #a0a0a5; background: rgba(0,0,0,0.15); padding: 4px 6px; border-radius: 4px; border: 1px solid rgba(255,255,255,0.03);">
            {{ currentProject ? currentProject.path : t('inspector.none') }}
          </span>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid rgba(255,255,255,0.04); padding-top: 8px;">
          <span style="color: var(--text-color-secondary); font-size: 0.85rem;">{{ t('overview.os') }}</span>
          <span style="color: var(--text-color-bright); font-size: 0.85rem;">Windows 11</span>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid rgba(255,255,255,0.04); padding-top: 8px;">
          <span style="color: var(--text-color-secondary); font-size: 0.85rem;">{{ t('overview.gitBranch') }}</span>
          <n-tag size="mini" round :bordered="false" style="font-family: monospace; color: #ffffff; background-color: rgba(255, 255, 255, 0.08);">
            {{ worktreeStore.selectedWorktree?.branch || t('inspector.none') }}
          </n-tag>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid rgba(255,255,255,0.04); padding-top: 8px;">
          <span style="color: var(--text-color-secondary); font-size: 0.85rem;">{{ t('overview.activeProcess') }}</span>
          <n-button
            secondary
            strong
            type="info"
            size="tiny"
            @click="showProcessModal = true"
          >
            {{ t('overview.viewDetails') }}
          </n-button>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid rgba(255,255,255,0.04); padding-top: 8px;">
          <span style="color: var(--text-color-secondary); font-size: 0.85rem;">{{ t('overview.activeNetwork') }}</span>
          <n-button
            secondary
            strong
            type="info"
            size="tiny"
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
      style="color: #767680; font-style: italic; font-size: 13px; padding: 16px; background: rgba(30, 30, 35, 0.4); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; text-align: center;"
    >
      {{ t('overview.noSubAgents') }}
    </div>
    <div
      v-else
      v-for="sub in filteredSubAgents"
      :key="sub.cid"
      :class="['subagent-card', sub.status?.toLowerCase()]"
      style="cursor: pointer; margin-bottom: 12px; position: relative;"
      @click="agentStore.openSubAgentDrawer(Number(sub.cid))"
    >
      <div class="subagent-header" style="display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px;">
        <span class="role" style="font-weight: bold; font-size: 0.85rem; color: var(--text-color-bright);">{{ sub.role }}</span>
        <sub-agent-status-tag :status="sub.status" />
      </div>
      <div class="subagent-task">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
          <span style="color: #81b6e5; font-family: monospace; font-weight: bold;">[ID: {{ sub.cid }}]</span>
        </div>

        <div style="font-size: 11px; margin-top: 4px; color: var(--text-color-secondary); display: flex; align-items: center; justify-content: space-between;">
          <token-metrics-tooltip
            :input-tokens="sub.inputTokens || 0"
            :output-tokens="sub.outputTokens || 0"
            :cached-tokens="sub.cachedTokens"
            label-prefix="Tokens: "
          />

          <!-- 待审批提示 -->
          <div v-if="sub.pendingPermissionReq" style="color: #f64c5d; font-weight: bold; display: flex; align-items: center; gap: 4px;">
            <span class="pulse-red-dot"></span>🚨 {{ t('subAgent.statusPendingPermission') }}
          </div>
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
      preset="dialog"
      title="活动子进程看板"
      style="width: 600px; background-color: #18181c; color: #fff;"
      :show-icon="false"
    >
      <div style="margin-top: 16px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
          <span style="font-size: 12px; color: #a0a0a5;">
            共检测到 <strong style="color: #f6a23c;">{{ activeProcessesList.length }}</strong> 个正在后台同步运行的物理进程（包含并行子代理拉起的进程）
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

        <div v-if="activeProcessesList.length === 0" style="padding: 24px; text-align: center; color: #767680; font-style: italic; background: rgba(0,0,0,0.15); border-radius: 6px;">
          暂无活动子进程，当智能体执行编译、运行测试等命令工具时在此展示。
        </div>

        <div v-else style="max-height: 400px; overflow-y: auto; display: flex; flex-direction: column; gap: 12px;">
          <div
            v-for="proc in activeProcessesList"
            :key="proc.toolCallId"
            style="padding: 12px; background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.06); border-radius: 6px; display: flex; flex-direction: column; gap: 6px;"
          >
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold; font-family: monospace; color: #81b6e5;">PID: {{ proc.pid }}</span>
              <div style="display: flex; align-items: center; gap: 8px;">
                <n-tag size="mini" type="info" round :bordered="false">
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
            
            <div style="font-size: 11px; color: #a0a0a5; word-break: break-all; background: rgba(0,0,0,0.2); padding: 4px 6px; border-radius: 4px; font-family: monospace;">
              {{ proc.command }}
            </div>

            <div style="display: flex; justify-content: space-between; align-items: center; font-size: 11px; color: #767680;">
              <span>工作目录: {{ proc.cwd }}</span>
              <span>运行时间: {{ formatDuration(proc.runningTimeMs) }}</span>
            </div>
          </div>
        </div>
      </div>
    </n-modal>

    <!-- 活动大模型连接详情控制弹窗 -->
    <n-modal
      v-model:show="showLlmModal"
      preset="dialog"
      title="活动 LLM 网络请求"
      style="width: 550px; background-color: #18181c; color: #fff;"
      :show-icon="false"
    >
      <div style="margin-top: 16px;">
        <div style="margin-bottom: 12px;">
          <span style="font-size: 12px; color: #a0a0a5;">
            共检测到 <strong style="color: #63e2b7;">{{ activeLlmCallsList.length }}</strong> 个正在进行的 LLM HTTP 网络请求
          </span>
        </div>

        <div v-if="activeLlmCallsList.length === 0" style="padding: 24px; text-align: center; color: #767680; font-style: italic; background: rgba(0,0,0,0.15); border-radius: 6px;">
          暂无活动 LLM 请求，当智能体发起大模型调用或进行会话压缩/重命名时在此展示。
        </div>

        <div v-else style="max-height: 400px; overflow-y: auto; display: flex; flex-direction: column; gap: 10px;">
          <div
            v-for="call in activeLlmCallsList"
            :key="call.llmCallId"
            style="padding: 12px; background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.06); border-radius: 6px; display: flex; flex-direction: column; gap: 6px;"
          >
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold; font-family: monospace; color: #63e2b7; font-size: 11px;">
                ID: {{ call.llmCallId }}
              </span>
              <n-tag size="mini" type="info" round :bordered="false">
                {{ call.sessionTitle }}
              </n-tag>
            </div>
            
            <div style="display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--text-color-bright);">
              <span>模型: <strong style="font-family: monospace; color: #f6a23c;">{{ call.model }}</strong></span>
              <span style="color: #767680;">耗时: {{ formatDuration(call.durationTimeMs) }}</span>
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
import { t } from '@/i18n'
import { useAgentStore } from '@/stores/agent'
import { useProjectStore } from '@/stores/project'
import { useConversationStore } from '@/stores/conversation'
import { useWorktreeStore } from '@/stores/worktree'
import TokenMetricsTooltip from '@/components/TokenMetricsTooltip.vue'
import SubAgentStatusTag from '@/components/SubAgentStatusTag.vue'
import { getConversationProcessesApi, killConversationProcessApi, ActiveProcessInfo, getConversationLlmCallsApi, ActiveLlmCallInfo } from '@/api/conversation'

const agentStore = useAgentStore()
const projectStore = useProjectStore()
const conversationStore = useConversationStore()
const worktreeStore = useWorktreeStore()
const message = useMessage()

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

const handleCopyPath = (path: string) => {
  if (!path) return
  navigator.clipboard.writeText(path).then(() => {
    message.success('项目物理路径已复制到剪贴板')
  }).catch(() => {
    message.error('物理路径复制失败')
  })
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
  background-color: #d03050;
  border-radius: 50%;
  display: inline-block;
  box-shadow: 0 0 0 0 rgba(208, 48, 80, 0.7);
  animation: pulse-dot 1.5s infinite;
}

@keyframes pulse-dot {
  0% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(208, 48, 80, 0.7);
  }
  70% {
    transform: scale(1);
    box-shadow: 0 0 0 5px rgba(208, 48, 80, 0);
  }
  100% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(208, 48, 80, 0);
  }
}

.subagent-card:hover {
  transform: translateY(-2px);
  background: rgba(35, 35, 42, 0.6);
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.3), 0 0 1px 1px rgba(255, 255, 255, 0.08);
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
  color: #ffffff;
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
