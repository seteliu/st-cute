import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { wsService } from '@/services/websocket'
import { getContextInfoApi } from '@/api/agent-context'
import { useConversationStore, trimMessagesArray } from './conversation'
import { useAppStore } from './app'
import { cancelConversationApi, approveConversationPermissionApi, getConversationMessages } from '@/api/conversation'
import {
  SubAgent,
  Skill,
  Hook,
  McpServer,
  AgentRule
} from '@/types'

export const useAgentStore = defineStore('agent', () => {
  const subAgents = ref<SubAgent[]>([])
  const activeSubAgentCid = ref<number | null>(null)
  const showSubAgentDrawer = ref(false)


  const skillsList = ref<Skill[]>([])
  const reloadingSkills = ref(false)

  const hooksList = ref<Hook[]>([])
  const reloadingHooks = ref(false)

  const mcpList = ref<McpServer[]>([])
  const reloadingServer = ref('')

  const rulesList = ref<AgentRule[]>([])
  const reloadingRules = ref(false)

  const activeSubAgent = computed(() => {
    if (!activeSubAgentCid.value) return null
    return subAgents.value.find(s => Number(s.cid) === activeSubAgentCid.value) || null
  })

  const openSubAgentDrawer = async (cid: number) => {
    activeSubAgentCid.value = cid
    showSubAgentDrawer.value = true

    const sub = getTargetAgent(cid)
    try {
      const res = await getConversationMessages(cid)
      sub.truncated = res.truncated || false
      sub.messages = (res.messages || []).map(msg => {
        if (msg.role) msg.role = msg.role.toLowerCase() as any
        return msg
      })

      // 同步获取其最新的 token 状态与 loopRunning 等，为数据做双重同步兜底
      const envInfo = await getContextInfoApi(cid)
      if (envInfo) {
        sub.inputTokens = envInfo.inputTokens || 0
        sub.outputTokens = envInfo.outputTokens || 0
        sub.cachedTokens = envInfo.cachedTokens || 0
        sub.currentIteration = envInfo.iterationCount || 0
        if (envInfo.loopRunning === 0 || envInfo.loopRunning === false) {
          if (sub.status === 'running') {
            sub.status = 'success'
          }
        } else {
          sub.status = 'running'
        }
      }
    } catch (e) {
      console.error('拉取历史子会话消息或上下文状态失败:', e)
    }
  }

  const getTargetAgent = (cid: number): SubAgent => {
    const cidStr = String(cid)
    let sub = subAgents.value.find(s => s.cid === cidStr)
    if (!sub) {
      sub = {
        role: 'SubAgent',
        typeName: 'self',
        workspace: 'inherit',
        cid: cidStr,
        parentCid: '',
        status: 'running',
        messages: [],
        truncated: false,
        currentIteration: 0,
        inputTokens: 0,
        outputTokens: 0,
        cachedTokens: 0
      }
      subAgents.value.push(sub)
    }
    return sub
  }

  const handleKillMember = () => {
    if (!activeSubAgent.value || !activeSubAgent.value.cid) return
    cancelConversationApi(Number(activeSubAgent.value.cid)).catch(err => {
      console.error('强杀成员会话失败:', err)
    })
    activeSubAgent.value.status = 'failed'
    activeSubAgent.value.messages.push({
      id: -Date.now(),
      role: 'system',
      content: '🚨 发送 CANCEL_LOOP 信号强杀会话！'
    })
  }

  const handleSubPermissionDecision = (subAgent: SubAgent, decision: 'ALLOW' | 'DENY') => {
    if (!subAgent.pendingPermissionReq) return

    let customArgOverride: string | undefined = undefined
    if (decision === 'ALLOW' && subAgent.pendingPermissionReq.isEditingArgs) {
      try {
        const parsed = JSON.parse(subAgent.pendingPermissionReq.editedArgumentsJson || '{}')
        customArgOverride = JSON.stringify(parsed)
      } catch (e) {
        customArgOverride = subAgent.pendingPermissionReq.editedArgumentsJson
      }
    }

    approveConversationPermissionApi(Number(subAgent.cid), {
      id: subAgent.pendingPermissionReq.id,
      decision: decision,
      alwaysAllow: false,
      toolName: subAgent.pendingPermissionReq.toolName,
      contentPattern: '',
      customArgOverride: customArgOverride
    }).catch(err => {
      console.error('审批子代理权限失败:', err)
    })

    subAgent.pendingPermissionReq = undefined
  }

  const deleteSubAgent = async (cid: number) => {
    const conversationStore = useConversationStore()
    try {
      await conversationStore.deleteConversation(cid)
      subAgents.value = subAgents.value.filter(s => {
        return Number(s.cid) !== cid
      })
      if (activeSubAgentCid.value === cid) {
        showSubAgentDrawer.value = false
        activeSubAgentCid.value = null
      }
      ;(window as any).$message?.success('删除子代理会话成功')
    } catch (e) {
      console.error('删除子代理会话失败:', e)
      ;(window as any).$message?.error('删除子代理会话失败')
    }
  }

  const syncSubAgents = (conversations: any[]) => {
    conversations.forEach(ce => {
      if (ce.parentCid) {
        const subCidStr = String(ce.id)
        const parentCidStr = String(ce.parentCid)
        let sub = subAgents.value.find(s => String(s.cid) === subCidStr)
        let role = ce.title || 'SubAgent'
        if (role.startsWith('SubAgent: ')) {
          role = role.substring('SubAgent: '.length)
        }

        const calculatedStatus = ce.loopRunning === 1 ? 'running' : 'success'

        if (!sub) {
          sub = {
            role: role,
            typeName: 'self',
            workspace: 'inherit',
            cid: subCidStr,
            parentCid: parentCidStr,
            status: calculatedStatus,
            messages: [],
            currentIteration: ce.iterationCount || 0,
            inputTokens: ce.inputTokens || 0,
            outputTokens: ce.outputTokens || 0,
            cachedTokens: ce.cachedTokens || 0
          }
          subAgents.value.push(sub)
        } else {
          // 同步已存在的子代理的最新运行状态、迭代轮数以及 Token 用量信息
          sub.status = calculatedStatus
          sub.currentIteration = ce.iterationCount || 0
          sub.inputTokens = ce.inputTokens || 0
          sub.outputTokens = ce.outputTokens || 0
          sub.cachedTokens = ce.cachedTokens || 0
        }
      }
    })
  }

  const loadSkills = async () => {
    try {
      const conversationStore = useConversationStore()
      const cid = conversationStore.activeCid
      if (cid === null) return
      const envInfo = await getContextInfoApi(cid)
      skillsList.value = envInfo.skills || []
    } catch (e) {
      console.error('加载技能列表失败:', e)
    }
  }

  const handleReloadSkills = async () => {
    reloadingSkills.value = true
    try {
      const conversationStore = useConversationStore()
      const success = await conversationStore.reloadProjectAssets()
      if (success) {
        await loadSkills()
      } else {
        if ((window as any).$message) {
          ;(window as any).$message.error('重载当前项目技能包失败')
        } else {
          console.error('重载当前项目技能包失败')
        }
      }
    } catch (e) {
      console.error('热扫描技能包失败:', e)
    } finally {
      reloadingSkills.value = false
    }
  }

  const loadHooks = async () => {
    try {
      const conversationStore = useConversationStore()
      const cid = conversationStore.activeCid
      if (cid === null) return
      const envInfo = await getContextInfoApi(cid)
      hooksList.value = envInfo.hooks || []
    } catch (e) {
      console.error('加载切面挂钩列表失败:', e)
    }
  }

  const handleReloadHooks = async () => {
    reloadingHooks.value = true
    try {
      const conversationStore = useConversationStore()
      const success = await conversationStore.reloadProjectAssets()
      if (success) {
        await loadHooks()
      } else {
        if ((window as any).$message) {
          ;(window as any).$message.error('重载当前项目挂钩配置失败')
        } else {
          console.error('重载当前项目挂钩配置失败')
        }
      }
    } catch (e) {
      console.error('热重载 Hook 失败:', e)
    } finally {
      reloadingHooks.value = false
    }
  }

  const loadMcpStatus = async () => {
    try {
      const conversationStore = useConversationStore()
      const cid = conversationStore.activeCid
      if (cid === null) return
      const envInfo = await getContextInfoApi(cid)
      mcpList.value = envInfo.mcpServers || []
    } catch (e) {
      console.error('加载 MCP 状态失败:', e)
    }
  }

  const handleReloadMcp = async (serverName: string) => {
    reloadingServer.value = serverName
    try {
      const conversationStore = useConversationStore()
      const success = await conversationStore.reloadProjectAssets()
      if (success) {
        await loadMcpStatus()
      } else {
        if ((window as any).$message) {
          ;(window as any).$message.error('重载 MCP 失败')
        } else {
          console.error('重载 MCP 失败')
        }
      }
    } catch (e) {
      console.error('重载 MCP 失败:', e)
    } finally {
      reloadingServer.value = ''
    }
  }

  const loadRules = async () => {
    try {
      const conversationStore = useConversationStore()
      const cid = conversationStore.activeCid
      if (cid === null) return
      const envInfo = await getContextInfoApi(cid)
      rulesList.value = envInfo.rules || []
    } catch (e) {
      console.error('加载项目规约列表失败:', e)
    }
  }

  const handleReloadRules = async () => {
    reloadingRules.value = true
    try {
      const conversationStore = useConversationStore()
      const success = await conversationStore.reloadProjectAssets()
      if (success) {
        await loadRules()
      } else {
        if ((window as any).$message) {
          ;(window as any).$message.error('重载当前项目规约失败')
        } else {
          console.error('重载当前项目规约失败')
        }
      }
    } catch (e) {
      console.error('热重载规约失败:', e)
    } finally {
      reloadingRules.value = false
    }
  }

  watch(
    () => subAgents.value,
    (agents) => {
      const appStore = useAppStore()
      const limit = appStore.maxViewHistoryLimit || 2000
      const buffer = 100

      agents.forEach((sub) => {
        if (sub.messages && sub.messages.length > limit + buffer) {
          const res = trimMessagesArray(sub.messages, limit)
          if (res.truncated) {
            sub.messages = res.list
            sub.truncated = true
          }
        }
      })
    },
    { deep: true }
  )

  return {
    subAgents,
    activeSubAgentCid,
    showSubAgentDrawer,
    activeSubAgent,
    skillsList,
    reloadingSkills,
    hooksList,
    reloadingHooks,
    mcpList,
    reloadingServer,
    rulesList,
    reloadingRules,
    
    openSubAgentDrawer,
    getTargetAgent,
    handleKillMember,
    deleteSubAgent,
    handleSubPermissionDecision,
    syncSubAgents,
    loadSkills,
    handleReloadSkills,
    loadHooks,
    handleReloadHooks,
    loadMcpStatus,
    handleReloadMcp,
    loadRules,
    handleReloadRules
  }
})
