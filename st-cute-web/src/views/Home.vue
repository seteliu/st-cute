<template>
  <div class="app-container">
    <n-layout has-sider class="main-layout">
      <!-- 左栏：会话目录 (桌面端) -->
      <left-sider v-if="!isMobile" />

      <!-- 中栏：当前会话聊天区 -->
      <chat-container />

      <!-- 右栏：审查与标签页面板 (桌面端) -->
      <right-sider v-if="!isMobile" />
    </n-layout>

    <!-- 移动端侧边栏 Drawer -->
    <n-drawer v-if="isMobile" v-model:show="showLeftDrawer" placement="left" :width="280" body-style="padding: 0; background-color: #18181c;">
      <left-sider />
    </n-drawer>
    <n-drawer v-if="isMobile" v-model:show="showRightDrawer" placement="right" :width="320" body-style="padding: 0; background-color: #18181c;">
      <right-sider />
    </n-drawer>

    <!-- 全局弹窗与抽屉 -->
    <permission-modal />
    <raw-log-drawer />
    <sub-agent-drawer />
    <thought-detail-drawer />
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, computed, watch } from 'vue'
import { initResponsive, useResponsive } from '@/utils/useResponsive'
import { wsService } from '@/services/websocket'
import { getConversationMessages } from '@/api/conversation'
import { Message } from '@/types'

// 状态 Store 引入
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useProviderStore } from '@/stores/provider'
import { useWorktreeStore } from '@/stores/worktree'
import { useAgentStore } from '@/stores/agent'
import { useProjectStore } from '@/stores/project'

// 布局组件引入
import LeftSider from '@/views/layout/LeftSider.vue'
import ChatContainer from '@/views/chat/ChatContainer.vue'
import RightSider from '@/views/layout/RightSider.vue'

// 弹框/抽屉组件引入
import PermissionModal from '@/views/dialogs/PermissionModal.vue'
import RawLogDrawer from '@/views/dialogs/RawLogDrawer.vue'
import SubAgentDrawer from '@/views/dialogs/SubAgentDrawer.vue'
import ThoughtDetailDrawer from '@/views/dialogs/ThoughtDetailDrawer.vue'
import { t } from '@/i18n'

const appStore = useAppStore()
const conversationStore = useConversationStore()
const providerStore = useProviderStore()
const worktreeStore = useWorktreeStore()
const agentStore = useAgentStore()
const projectStore = useProjectStore()

const { isMobile } = useResponsive()

const showLeftDrawer = computed({
  get: () => !appStore.leftSiderCollapsed,
  set: (val) => { appStore.leftSiderCollapsed = !val }
})
const showRightDrawer = computed({
  get: () => !appStore.rightSiderCollapsed,
  set: (val) => { appStore.rightSiderCollapsed = !val }
})

watch(isMobile, (newVal) => {
  if (newVal) {
    appStore.leftSiderCollapsed = true
    appStore.rightSiderCollapsed = true
  }
}, { immediate: true })

let worktreeTimer: any = null

onMounted(async () => {
  // 0. 初始化移动端响应式检测
  initResponsive()

  // 防御性清空先前残留的 WS 回调监听器，彻底解决 HMR 或重复 mount 导致的流式内容叠加 Bug
  wsService.clearAllCallbacks()

  // 1. 初始化轮询与数据加载
  worktreeStore.fetchWorktrees()
  worktreeTimer = window.setInterval(() => {
    worktreeStore.fetchWorktrees(true)
  }, 10000)

  try {
    // 1. 并行加载与项目无关的全局系统配置底座
    const res1 = await Promise.allSettled([
      appStore.loadBasicConfig(),
      providerStore.loadProviders()
    ])
    if (res1[0].status === 'rejected') {
      ;(window as any).$message?.error(t('home.basicConfigError'))
    }
    if (res1[1].status === 'rejected') {
      ;(window as any).$message?.error(t('home.providerConfigError'))
    }

    // 2. 严格串行加载项目列表与依赖它的会话列表
    try {
      await projectStore.loadProjects()
    } catch (e) {
      ;(window as any).$message?.error(t('home.projectListError'))
    }
    
    try {
      await conversationStore.loadConversations()
    } catch (e) {
      ;(window as any).$message?.error(t('home.historyError'))
    }

    // 3. 项目与会话就绪后，并行加载与当前项目上下文绑定的专属扩展资源
    const res3 = await Promise.allSettled([
      agentStore.loadMcpStatus(),
      agentStore.loadSkills(),
      agentStore.loadHooks()
    ])
    if (res3[0].status === 'rejected') {
      ;(window as any).$message?.error(t('home.mcpError'))
    }
    if (res3[1].status === 'rejected') {
      ;(window as any).$message?.error(t('home.skillsError'))
    }
    if (res3[2].status === 'rejected') {
      ;(window as any).$message?.error(t('home.hooksError'))
    }
  } catch (e) {
    console.error('初始化配置拉取发生异常:', e)
  } finally {
    appStore.isInitialized = true
  }

  // 2. 开启 WebSocket 长连接
  wsService.connect()

  // 3. 监听网络连接状态
  let isFirstWsOpen = true
  wsService.on('OPEN', () => {
    appStore.isConnected = true
    if (!isFirstWsOpen) {
      // 断线重连后：优先强刷当前会话详情（消息区立即可见可交互，用户最先感知），
      // 再静默后台全量刷新会话列表（移动端列表通常隐藏，优先级低且不该阻塞详情刷新）。
      // 离线期间错过的 S2C_CONVERSATION_STATUS 广播会让列表中的 loopRunning 转圈残留旧值，
      // 后台重拉一次以数据库真值对齐全部会话状态，避免"发送按钮已停转、列表仍在转圈"的分裂观感
      if (conversationStore.activeCid !== null) {
        conversationStore.selectConversation(conversationStore.activeCid, true)
      }
      conversationStore.loadConversations().catch(e => {
        console.error('断线重连后会话列表刷新失败:', e)
      })
    }
    isFirstWsOpen = false
  })
  wsService.on('CLOSE', () => {
    appStore.isConnected = false
    appStore.loopRunning = false
  })

  // 判定是否是子会话。排除 parentCid 值为 0 或 '0' 的假子会话情况（0 代表主会话本身无父智能体）
  const isSubConversation = (parentCid: any): boolean => {
    return parentCid !== undefined && parentCid !== null && parentCid !== 0 && parentCid !== '0'
  }

  // 判定并过滤非当前活动主/子会话消息的拦截器
  const shouldProcessEvent = (event: any): boolean => {
    const parentCid = event.parentCid
    const cid = event.cid
    if (cid === null || cid === undefined) return false

    const isSub = isSubConversation(parentCid)
    if (isSub) {
      // 子会话：只要其父会话 ID 是当前活动的主会话 ID 就处理，以在后台累积其消息
      return Number(parentCid) === conversationStore.activeCid
    } else {
      // 主会话：只在消息的 cid 和当前活动主会话的 activeCid 完全一致时才处理
      return Number(cid) === conversationStore.activeCid
    }
  }

  // 监听大局会话创建事件，实现子 Agent 创建 of 即时卡片渲染
  wsService.on('S2C_CONVERSATION_CREATED', (event) => {
    const payload = event.payload
    if (!payload) return

    const subParentCid = payload.parentCid
    const isSub = isSubConversation(subParentCid)

    if (isSub) {
      if (Number(subParentCid) === conversationStore.activeCid) {
        const sub = agentStore.getTargetAgent(Number(payload.id))
        if (sub) {
          sub.parentCid = String(subParentCid)
          sub.workspace = payload.workspace || 'inherit'
          sub.typeName = payload.typeName || 'self'
          sub.status = 'running'
          let role = payload.title || 'SubAgent'
          if (role.startsWith('SubAgent: ')) {
            role = role.substring('SubAgent: '.length)
          }
          sub.role = role
          sub.currentIteration = payload.iterationCount || 0
        }
      }
    } else {
      conversationStore.loadConversations()
    }
  })

  // 监听大局会话整建制更新事件
  wsService.on('S2C_CONVERSATION_UPDATED', (event) => {
    const payload = event.payload
    const cid = Number(event.cid)
    const parentCid = event.parentCid

    if (isSubConversation(parentCid)) {
      // 说明是子会话的更新：只要其父会话 ID 是当前活动主会话，就同步其最新的状态更新
      if (Number(parentCid) === conversationStore.activeCid) {
        const sub = agentStore.getTargetAgent(cid)
        if (sub) {
          sub.currentIteration = payload.iterationCount
          sub.inputTokens = payload.inputTokens
          sub.outputTokens = payload.outputTokens
          sub.cachedTokens = payload.cachedTokens
          if (payload.loopRunning === 0) {
            if (sub.status === 'running') {
              sub.status = 'success'
            }
          } else {
            sub.status = 'running'
          }
        }
      }
    } else {
      if (cid === conversationStore.activeCid) {
        appStore.loopRunning = payload.loopRunning === 1
        appStore.permissionMode = payload.permissionMode
        appStore.currentIteration = payload.iterationCount

        conversationStore.inputTokens = payload.inputTokens
        conversationStore.outputTokens = payload.outputTokens
        conversationStore.cachedTokens = payload.cachedTokens

        // 重新加载会话列表，以同步最新的并发子会话卡片等信息
        conversationStore.loadConversations()
      }
    }

    // 同步更新会话列表中对应的那个
    const currentSess = conversationStore.conversationList.find(s => s.id === cid)
    if (currentSess) {
      currentSess.inputTokens = payload.inputTokens
      currentSess.outputTokens = payload.outputTokens
      currentSess.cachedTokens = payload.cachedTokens
      currentSess.loopRunning = payload.loopRunning
      currentSess.permissionMode = payload.permissionMode
      currentSess.iterationCount = payload.iterationCount
      currentSess.waitingToolIds = payload.waitingToolIds
      currentSess.waitingSubCids = payload.waitingSubCids
      currentSess.providerGroup = payload.providerGroup
      currentSess.providerModelName = payload.providerModelName
    }
  })

  // 监听会话运行状态广播（全局广播、刻意不经过 shouldProcessEvent 过滤）：
  // 让所有客户端的会话列表都能实时感知任意主会话的 running 状态，驱动转圈监控效果
  wsService.on('S2C_CONVERSATION_STATUS', (event) => {
    const payload = event.payload
    if (!payload || payload.id === undefined || payload.id === null) return

    const target = conversationStore.conversationList.find(s => s.id === Number(payload.id))
    if (target) {
      target.loopRunning = payload.loopRunning
      // 同步刷新名称与最后更新时间，保持列表项展示与后端一致
      if (payload.title) target.title = payload.title
      if (payload.updatedAt) target.updatedAt = payload.updatedAt
    }
  })

  // 监听后端创建消息占位事件
  wsService.on('S2C_MESSAGE_CREATED', (event) => {
    if (!shouldProcessEvent(event)) return
    const payload = event.payload
    const parentCid = event.parentCid
    const cid = event.cid

    if (payload && payload.id) {
      const newMsg = payload
      if (newMsg.role) newMsg.role = newMsg.role.toLowerCase()
      
      const isSub = isSubConversation(parentCid)
      const targetMessages = isSub
        ? (agentStore.getTargetAgent(Number(cid))?.messages || [])
        : conversationStore.messages

      if (!targetMessages.some((m: any) => m.id === newMsg.id)) {
        targetMessages.push(newMsg)
      }

      // 额外解析并自愈子会话的 role
      if (isSub && newMsg.role === 'user' && typeof newMsg.content === 'string') {
        const match = newMsg.content.match(/^\[SubAgent 派发任务 - 角色:\s*(.+?)\]/)
        if (match && match[1]) {
          const sub = agentStore.getTargetAgent(Number(cid))
          if (sub) {
            sub.role = match[1].trim()
          }
        }
      }
    }
  })

  // 监听消息状态及内容更新事件
  wsService.on('S2C_MESSAGE_UPDATED', (event) => {
    if (!shouldProcessEvent(event)) return
    const payload = event.payload
    const parentCid = event.parentCid
    const cid = event.cid

    if (payload && payload.id) {
      if (payload.role) payload.role = payload.role.toLowerCase()

      const isSub = isSubConversation(parentCid)
      const targetMessages = isSub
        ? (agentStore.getTargetAgent(Number(cid))?.messages || [])
        : conversationStore.messages

      const target = targetMessages.find((m: any) => m.id === payload.id)
      if (target) {
        // 如果本地消息正在流式追加内容，则不能用服务端下发的 content 覆盖
        // 否则会导致：流式输出快结束时，S2C_MESSAGE_UPDATED 将本地累积内容清空，消息块短暂消失
        const isLocalStreaming = target.isStreaming === true
        // 额外保护：即使 isStreaming 已结束，若本地已累积了较长的内容而服务端下发空内容
        // （常见于服务端在流式完成后的 UPDATE 事件中 content 字段为 null/empty），也不覆盖
        const serverSentEmptyContent = (payload.content === null || payload.content === undefined || payload.content === '')
        const localHasContent = target.content && target.content.length > 0
        const isPending = payload.status === 'PENDING'

        if (!isPending && (isLocalStreaming || (serverSentEmptyContent && localHasContent))) {
          // 流式中或服务端给空内容：只更新非内容字段（状态、id、role等），保留本地累积的 content 和 thought
          const { content, thought, isStreaming, ...restPayload } = payload
          Object.assign(target, restPayload)
        } else {
          // 正常情况：完全覆盖
          Object.assign(target, payload)
        }
      } else {
        targetMessages.push(payload)
      }

      if (payload.role === 'assistant' && (payload.status === 'SUCCESS' || payload.status === 'FAILED' || payload.status === 'CANCELED')) {
        if (!isSub) {
          appStore.loopRunning = false
        }
      }
      if (payload.status === 'FAILED' || payload.status === 'CANCELED') {
        if (!isSub) {
          appStore.loopRunning = false
        } else {
          // 如果是子代理的消息变成 FAILED/CANCELED，则将该子代理的运行状态设为 failed
          const sub = agentStore.getTargetAgent(Number(cid))
          if (sub) {
            sub.status = 'failed'
          }
        }
      }
    }
  })

  // 8. Hook 生命周期切面日志同步
  wsService.on('S2C_HOOK_EVENT', (event) => {
    if (!shouldProcessEvent(event)) return
    const payload = event.payload
    const toolCallId = payload.toolCallId
    const parentCid = event.parentCid
    const cid = event.cid

    if (toolCallId) {
      const targetMessages = isSubConversation(parentCid)
        ? (agentStore.getTargetAgent(Number(cid))?.messages || [])
        : conversationStore.messages

      const targetMsg = targetMessages.find((m: any) => m.role === 'tool' && m.toolId === toolCallId)
      if (targetMsg) {
        if (!targetMsg.hooks) {
          targetMsg.hooks = []
        }
        const hk = targetMsg.hooks.find((h: any) => h.name === payload.name)
        if (hk) {
          hk.status = payload.status
          hk.error = payload.error
        } else {
          targetMsg.hooks.push({
            name: payload.name,
            status: payload.status,
            error: payload.error
          })
        }
      }
    }
  })

  const handleChatStream = (event: any, isReasoning: boolean) => {
    if (!shouldProcessEvent(event)) return
    const payload = event.payload
    const parentCid = event.parentCid
    const cid = event.cid

    if (isSubConversation(parentCid)) {
      const sub = agentStore.getTargetAgent(Number(cid))
      if (sub) {
        let currentMsg = null
        if (payload.messageId) {
          currentMsg = sub.messages.find((m: any) => m.id === payload.messageId)
        }
        if (!currentMsg) {
          currentMsg = sub.messages.find((m: any) => m.role === 'assistant' && (m.isStreaming || m.status === 'RUNNING'))
        }
        if (!currentMsg) {
          const newMsg: Message = {
            id: payload.messageId || -Date.now(),
            role: 'assistant',
            content: '',
            thought: '',
            isStreaming: true,
            status: 'RUNNING'
          }
          sub.messages.push(newMsg)
          currentMsg = newMsg
        }
        if (isReasoning) {
          currentMsg.thought = (currentMsg.thought || '') + payload.content
        } else {
          currentMsg.content += payload.content
        }
        if (payload.isEnd) {
          currentMsg.isStreaming = false
          currentMsg.status = 'SUCCESS'
          sub.status = 'success'
        }
      }
      return
    }

    let currentMsg = null
    if (payload.messageId) {
      currentMsg = conversationStore.messages.find(m => m.id === payload.messageId)
    }
    if (!currentMsg) {
      currentMsg = conversationStore.messages.find(m => m.role === 'assistant' && (m.isStreaming || m.status === 'RUNNING'))
    }
    if (!currentMsg && (payload.content || isReasoning)) {
      currentMsg = {
        id: payload.messageId || ('assistant_' + Date.now()),
        role: 'assistant' as const,
        content: '',
        thought: '',
        isStreaming: true,
        status: 'RUNNING' as const
      }
      conversationStore.messages.push(currentMsg)
    }

    if (currentMsg) {
      if (isReasoning) {
        currentMsg.thought = (currentMsg.thought || '') + payload.content
      } else {
        currentMsg.content += payload.content
      }
      if (payload.isEnd) {
        currentMsg.isStreaming = false
        if (currentMsg.status === 'RUNNING') {
          currentMsg.status = 'SUCCESS'
        }
        appStore.loopRunning = false
        conversationStore.loadConversations()
      }
    }
  }

  // 9. 大模型响应流式渲染 (思维过程与正文回答拆分)
  wsService.on('S2C_THINKING_STREAM', (event) => {
    handleChatStream(event, true)
  })
  wsService.on('S2C_CONTENT_STREAM', (event) => {
    handleChatStream(event, false)
  })

  // 监听大局历史消息物理删除事件
  wsService.on('S2C_MESSAGE_DELETED', (event) => {
    if (!shouldProcessEvent(event)) return
    const payload = event.payload
    const targetId = Number(payload.id)
    const cid = Number(event.cid)
    const parentCid = event.parentCid

    const isSub = isSubConversation(parentCid)
    if (isSub) {
      const sub = agentStore.getTargetAgent(Number(cid))
      if (sub) {
        sub.messages = sub.messages.filter((m: any) => m.id <= targetId)
      }
    } else {
      if (cid === conversationStore.activeCid) {
        conversationStore.messages = conversationStore.messages.filter((m: any) => m.id <= targetId)
        appStore.loopRunning = false
      }
    }
  })

  // 监听物理会话删除事件，保障多客户端/多窗口实时同步
  wsService.on('S2C_CONVERSATION_DELETED', (event) => {
    const deletedCid = Number(event.payload)
    if (!deletedCid) return

    conversationStore.conversationList = conversationStore.conversationList.filter(s => s.id !== deletedCid)

    if (conversationStore.activeCid === deletedCid) {
      conversationStore.activeCid = null
      if (conversationStore.conversationList.length > 0) {
        conversationStore.selectConversation(conversationStore.conversationList[0].id)
      } else {
        conversationStore.createConversation()
      }
    }
  })

  // 监听项目添加事件，保障多客户端/多窗口实时同步
  wsService.on('S2C_PROJECT_CREATED', async (event) => {
    await projectStore.loadProjects()
  })

  // 监听项目删除事件，保障多客户端/多窗口实时同步
  wsService.on('S2C_PROJECT_DELETED', async (event) => {
    const deletedId = Number(event.payload)
    if (!deletedId) return

    if (projectStore.activeProjectId === deletedId) {
      projectStore.activeProjectId = null
    }
    await projectStore.loadProjects()
    await conversationStore.loadConversations()
  })

  // 监听系统配置更新事件，保障多客户端/多窗口实时同步
  wsService.on('S2C_CONFIG_UPDATED', (event) => {
    if (event.payload) {
      appStore.newlineKey = event.payload.newlineKey || 'enter'
      appStore.httpLog = event.payload.httpLog || false
      appStore.httpLogDays = event.payload.httpLogDays !== undefined ? event.payload.httpLogDays : 7
      appStore.password = event.payload.password || ''
      appStore.messageAggregation = event.payload.messageAggregation !== undefined ? event.payload.messageAggregation : true
    }
  })

  // 监听供应商配置更新事件，保障多客户端/多窗口实时同步
  wsService.on('S2C_PROVIDERS_UPDATED', (event) => {
    if (event.payload) {
      providerStore.providerList = event.payload
    } else {
      providerStore.loadProviders()
    }
  })

  // 10. 大模型会话生成错误拦截
  wsService.on('S2C_CHAT_ERROR', (event) => {
    const payload = event.payload

    appStore.loopRunning = false

    const errMsg = payload.errorMsg || '大模型对话发生未知异常'
    if ((window as any).$message) {
      ;(window as any).$message.error(`[对话异常] ${errMsg}`)
    } else {
      console.error(`[对话异常] ${errMsg}`)
    }

    const activeCid = conversationStore.activeCid
    if (activeCid !== null) {
      getConversationMessages(activeCid).then(res => {
        const list = res.messages || []
        conversationStore.truncated = res.truncated || false
        conversationStore.messages = list.map(msg => {
          if (msg.role) msg.role = msg.role.toLowerCase() as any
          return msg
        })
      }).catch(err => {
        console.error('自愈刷新消息列表失败:', err)
      })
    }
  })

  // 15. 人在回路权限审批询问
  wsService.on('S2C_PERMISSION_REQUEST', (event) => {
    const payload = event.payload
    const parentCid = event.parentCid
    const cid = event.cid

    if (isSubConversation(parentCid)) {
      const sub = agentStore.getTargetAgent(Number(cid))
      if (sub) {
        sub.pendingPermissionReq = {
          id: payload.id,
          toolName: payload.toolName,
          arguments: payload.arguments,
          isEditingArgs: false,
          editedArgumentsJson: appStore.formatArgumentsJson(payload.arguments)
        }
      }
      return
    }

    appStore.currentPermissionReq = {
      id: payload.id,
      toolName: payload.toolName,
      arguments: payload.arguments
    }
    appStore.isEditingArgs = false
    appStore.editedArgumentsJson = appStore.formatArgumentsJson(payload.arguments)
    appStore.alwaysAllowChecked = false
    appStore.showPermissionModal = true
  })
})

onUnmounted(() => {
  wsService.close()
  wsService.clearAllCallbacks()
  if (worktreeTimer) {
    clearInterval(worktreeTimer)
  }
})
</script>
