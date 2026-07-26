import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { wsService } from '@/services/websocket'
import {
  getConversations,
  getConversationMessages,
  deleteConversationById,
  createConversationApi,
  updateConversationProviderApi,
  clearConversationMessagesApi,
  resetConversationMessagesApi,
  sendMessageApi,
  retryMessageApi,
  renameConversationApi
} from '@/api/conversation'
import { getContextInfoApi, reloadContextAssetsApi } from '@/api/agent-context'
import { useAppStore } from './app'
import { useProviderStore } from './provider'
import { useProjectStore } from './project'
import { useAgentStore } from './agent'
import { Message, Conversation } from '@/types'

export const useConversationStore = defineStore('conversation', () => {
  const conversationList = ref<Conversation[]>([])
  const activeCid = ref<number | null>(null)
  const messages = ref<Message[]>([])
  const truncated = ref(false)
  const isMessageLoading = ref(false)
  const isMessageSpinning = ref(false)
  const inputTokens = ref(0)
  const outputTokens = ref(0)
  const cachedTokens = ref(0)

  const appStore = useAppStore()
  const projectStore = useProjectStore()

  const clearContext = () => {
    const agentStore = useAgentStore()
    agentStore.skillsList = []
    agentStore.hooksList = []
    agentStore.mcpList = []
    agentStore.rulesList = []
    messages.value = []
    activeCid.value = null
    inputTokens.value = 0
    outputTokens.value = 0
    cachedTokens.value = 0
  }

  // 加载会话列表
  const loadConversations = async () => {
    try {
      const data = await getConversations()
      // 过滤出绑定了有效项目的会话，防止历史孤儿脏数据干扰
      const validConversations = data.filter(s => 
        s.projectId && projectStore.projectList.some(p => p.id === s.projectId)
      )
      conversationList.value = validConversations
      
      // 自动从历史会话中提取并同步子智能体到 agentStore 中
      const agentStore = useAgentStore()
      agentStore.syncSubAgents(validConversations)

      // 如果项目列表被删光了，说明系统无项目，应该彻底清空所有环境资产和会话并返回
      if (projectStore.projectList.length === 0) {
        clearContext()
        return
      }

      // 如果当前的 activeCid 不为空，但其指向的会话不在有效列表中（即它已经被级联删除了）
      if (activeCid.value !== null && !validConversations.some(s => s.id === activeCid.value)) {
        activeCid.value = null
      }

      if (activeCid.value === null) {
        // 默认选中时，只能选中 parentCid 为空的主会话，避免刷新后展示子会话的问题
        const mainConversations = validConversations.filter(s => !s.parentCid)
        if (mainConversations.length > 0) {
          await selectConversation(mainConversations[0].id)
        } else if (projectStore.projectList.length > 0) {
          await createConversation()
        }
      }
    } catch (e) {
      console.error('加载历史会话失败:', e)
    }
  }

  // 选择会话
  const selectConversation = async (id: number, force = false) => {
    if (!force && activeCid.value === id) return
    activeCid.value = id
    wsService.setCid(id)
    isMessageLoading.value = true
    isMessageSpinning.value = true

    // 联动切换当前选中的项目并初始化 Token 用量展示
    const sess = conversationList.value.find(s => s.id === id)
    if (sess) {
      if (sess.projectId) {
        projectStore.activeProjectId = sess.projectId
      }
      inputTokens.value = sess.inputTokens || 0
      outputTokens.value = sess.outputTokens || 0
      cachedTokens.value = sess.cachedTokens || 0
    } else {
      inputTokens.value = 0
      outputTokens.value = 0
      cachedTokens.value = 0
    }

    try {
      const res = await getConversationMessages(id)
      truncated.value = res.truncated || false
      const list = res.messages || []
      
      messages.value = list.map((msg) => {
        const roleLower = msg.role ? (msg.role.toLowerCase() as any) : 'assistant'
        return {
          ...msg,
          role: roleLower
        }
      })

      // 获取当前环境信息（包括 token、skill、hook、mcp 等）并同步分发至 Store
      const envInfo = await getContextInfoApi(id)
      const agentStore = useAgentStore()
      agentStore.skillsList = envInfo.skills || []
      agentStore.hooksList = envInfo.hooks || []
      agentStore.mcpList = envInfo.mcpServers || []
      agentStore.rulesList = envInfo.rules || []
      if (envInfo.permissionMode) {
        appStore.permissionMode = envInfo.permissionMode
      } else {
        appStore.permissionMode = 'READ_ONLY'
      }
      
      // 顺便同步最新的 Token 用量与 LoopRunning 状态
      inputTokens.value = envInfo.inputTokens || 0
      outputTokens.value = envInfo.outputTokens || 0
      cachedTokens.value = envInfo.cachedTokens || 0
      if (envInfo.loopRunning !== undefined) {
        appStore.loopRunning = envInfo.loopRunning
      }
    } catch (e) {
      console.error('加载历史消息与会话状态失败:', e)
      ;(window as any).$message?.error('加载历史消息与会话状态失败，请检查网络或后端连接')
    } finally {
      isMessageLoading.value = false
      // 150ms 后停止转轮背景，保持首屏灵敏度
      setTimeout(() => {
        isMessageSpinning.value = false
      }, 150)
    }
  }

  // 新建会话
  const createConversation = async (projectId?: number) => {
    const pId = projectId || projectStore.activeProjectId
    if (pId === null || pId === undefined) {
      // 如果没有任何项目，则不能新建会话
      return
    }
    
    const payload: Partial<Conversation> = {
      title: '新会话',
      projectId: pId
    }

    try {
      const saved = await createConversationApi(payload)
      activeCid.value = saved.id
      wsService.setCid(saved.id)
      messages.value = []
      conversationList.value.unshift(saved)
      
      // 关键！主动触发选中会话与加载最新的环境上下文资产
      await selectConversation(saved.id, true)
    } catch (e) {
      console.error('创建会话失败:', e)
    }
  }

  // 删除会话
  const deleteConversation = async (id: number) => {
    try {
      await deleteConversationById(id)
      conversationList.value = conversationList.value.filter(s => s.id !== id)

      if (activeCid.value === id) {
        activeCid.value = null
        if (conversationList.value.length > 0) {
          await selectConversation(conversationList.value[0].id)
        } else {
          clearContext()
          createConversation()
        }
      }
    } catch (e) {
      console.error('删除会话失败:', e)
    }
  }

  // 发送消息
  const sendUserMsg = () => {
    const text = appStore.userInput.trim()
    if (!text || appStore.loopRunning) return
    
    const id = activeCid.value
    if (id === null) return

    // 检查供应商是否为空
    const sess = conversationList.value.find(s => s.id === id)
    const providerStore = useProviderStore()
    let pGroup = sess?.providerGroup
    if (!pGroup && providerStore.providerList.length > 0) {
      pGroup = providerStore.providerList[0].group
    }
    if (!pGroup) {
      if ((window as any).$message) {
        ;(window as any).$message.warning('请先在左侧“系统设置”中配置并添加大模型供应商 (Provider)，当前无法发起对话。')
      } else {
        console.warn('请先在左侧“系统设置”中配置并添加大模型供应商 (Provider)，当前无法发起对话。')
      }
      return
    }

    appStore.currentIteration = 0
    
    const oldInput = appStore.userInput
    appStore.userInput = ''
    appStore.loopRunning = true
    
    sendMessageApi(id, { text }).then(() => {
      // 成功发送后无需手动在此处更新，等待 WebSocket 推送 S2C_MESSAGE_CREATED 事件后自动追加
    }).catch(err => {
      console.error('发送消息失败:', err)
      appStore.loopRunning = false
      appStore.userInput = oldInput
      const errMsg = err.response?.data?.msg || err.message || '发送消息失败，请检查网络或后端状态'
      if ((window as any).$message) {
        ;(window as any).$message.error(errMsg)
      } else {
        console.error(errMsg)
      }
    })
  }

  // 清空当前会话历史
  const clearConversation = async () => {
    const id = activeCid.value
    if (id === null) return
    try {
      await clearConversationMessagesApi(id)
      truncated.value = false
      messages.value = [
        {
          id: -Date.now(),
          role: 'system',
          content: '✨ 对话上下文已清空，重置 Token 计数'
        }
      ]
      inputTokens.value = 0
      outputTokens.value = 0
      cachedTokens.value = 0
      appStore.currentIteration = 0
    } catch (e) {
      console.error('清空会话失败:', e)
    }
  }


  // 手动重试消息
  const retryMessage = (messageId: number) => {
    const id = activeCid.value
    if (id === null) return
    appStore.loopRunning = true

    // 即时本地清空旧的生成内容与状态，提供更敏捷的用户视觉反馈
    const target = messages.value.find(m => m.id === messageId)
    if (target) {
      target.content = ''
      target.thought = ''
      target.status = 'PENDING'
      target.toolCalls = undefined
    }

    retryMessageApi(id, messageId, { agentId: 'main' }).then(() => {
      // 重试成功后，WS 会广播对应的 S2C_MESSAGE_UPDATED 事件来刷新列表状态，此处无需手动更新
    }).catch(err => {
      console.error('重试消息失败:', err)
      appStore.loopRunning = false
    })
  }

  // 重置会话至指定消息节点
  const resetToMessage = async (messageId: number) => {
    const id = activeCid.value
    if (id === null) return
    try {
      await resetConversationMessagesApi(id, messageId)
      // 重置成功后，WS 会广播对应的删除与更新事件来刷新消息列表，此处无需手动更新整个列表
    } catch (e) {
      console.error('重置会话消息历史失败:', e)
      throw e
    }
  }

  // 一键热重载项目资产
  const reloadProjectAssets = async () => {
    const id = activeCid.value
    if (id === null) return false
    try {
      const success = await reloadContextAssetsApi(id)
      if (success) {
        // 重载成功后，再次获取最新的环境信息并静默刷新 Store 中的资产状态
        const envInfo = await getContextInfoApi(id)
        const agentStore = useAgentStore()
        agentStore.skillsList = envInfo.skills || []
        agentStore.hooksList = envInfo.hooks || []
        agentStore.mcpList = envInfo.mcpServers || []
        agentStore.rulesList = envInfo.rules || []
        if (envInfo.permissionMode) {
          appStore.permissionMode = envInfo.permissionMode
        } else {
          appStore.permissionMode = 'READ_ONLY'
        }
      }
      return success
    } catch (e) {
      console.error('热重载项目资产失败:', e)
      return false
    }
  }

  // 修改会话名称
  const renameConversation = async (id: number, title: string) => {
    try {
      await renameConversationApi(id, title)
      const sess = conversationList.value.find(s => s.id === id)
      if (sess) {
        sess.title = title
      }
    } catch (e) {
      console.error('修改会话名称失败:', e)
      throw e
    }
  }

  watch(
    () => messages.value.length,
    (newLength) => {
      const appStore = useAppStore()
      const limit = appStore.maxViewHistoryLimit || 2000
      const buffer = 100

      if (newLength > limit + buffer) {
        const res = trimMessagesArray(messages.value, limit)
        if (res.truncated) {
          messages.value = res.list
          truncated.value = true
        }
      }
    }
  )

  return {
    conversationList,
    activeCid,
    messages,
    truncated,
    isMessageLoading,
    isMessageSpinning,
    inputTokens,
    outputTokens,
    cachedTokens,
    loadConversations,
    selectConversation,
    createConversation,
    deleteConversation,
    sendUserMsg,
    clearConversation,
    retryMessage,
    resetToMessage,
    reloadProjectAssets,
    renameConversation
  }
})

export function trimMessagesArray(messages: any[], limit: number): { list: any[]; truncated: boolean } {
  if (messages.length <= limit) {
    return { list: messages, truncated: false }
  }

  const total = messages.length
  let targetIndex = total - limit
  let startIndex = 0

  while (targetIndex >= 0) {
    const msg = messages[targetIndex]
    if (msg && (msg.role === 'user' || msg.role === 'USER')) {
      startIndex = targetIndex
      break
    }
    targetIndex--
  }

  if (startIndex > 0) {
    return { list: messages.slice(startIndex), truncated: true }
  }
  return { list: messages, truncated: false }
}
