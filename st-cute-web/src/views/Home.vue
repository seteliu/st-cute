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
    <raw-log-drawer />
    <sub-agent-drawer />
    <thought-detail-drawer />
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, computed, watch } from 'vue'
import { initResponsive, useResponsive } from '@/utils/useResponsive'
import { wsService } from '@/services/websocket'
import { Message, StreamChunkPayload } from '@/types'
import {
  appendIncomingMessage,
  isInFoldedRange,
  onAssistantTerminal,
  onToolWaitingApproval,
  applyResetDeletion
} from '@/utils/foldEngine'

// 状态 Store 引入
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useProviderStore } from '@/stores/provider'
import { useGitStore } from '@/stores/git'
import { useAgentStore } from '@/stores/agent'
import { useProjectStore } from '@/stores/project'

// 布局组件引入
import LeftSider from '@/views/layout/LeftSider.vue'
import ChatContainer from '@/views/chat/ChatContainer.vue'
import RightSider from '@/views/layout/RightSider.vue'

// 弹框/抽屉组件引入
import RawLogDrawer from '@/views/dialogs/RawLogDrawer.vue'
import SubAgentDrawer from '@/views/dialogs/SubAgentDrawer.vue'
import ThoughtDetailDrawer from '@/views/dialogs/ThoughtDetailDrawer.vue'
import { t } from '@/i18n'

const appStore = useAppStore()
const conversationStore = useConversationStore()
const providerStore = useProviderStore()
const gitStore = useGitStore()
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

let gitTimer: any = null

onMounted(async () => {
  // 0. 初始化移动端响应式检测
  initResponsive()

  // 防御性清空先前残留的 WS 回调监听器，彻底解决 HMR 或重复 mount 导致的流式内容叠加 Bug
  wsService.clearAllCallbacks()

  // 1. 初始化轮询（首次不主动拉取：此刻 activeCid 尚未就绪，fetchBranches 只会空转清态；
  //    真正的首次拉取由后续 selectConversation 的 loadEnvAssets 链触发）
  gitTimer = window.setInterval(() => {
    gitStore.fetchBranches(true)
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
      // 离线期间错过的 S2C_CONVERSATION_UPDATED 广播会让列表中的 loopRunning 转圈残留旧值，
      // 后台重拉一次以数据库真值对齐全部会话状态，避免"发送按钮已停转、列表仍在转圈"的分裂观感
      // 会话详情强刷走静默模式：快速重连（1 秒内完成）全程无转圈无动画，超时才降级显示加载态；
      // HTTP 层同步静默（不弹网络错误弹窗），消息加载失败会自动重试，耗尽后才一次性提示
      if (conversationStore.activeCid !== null) {
        conversationStore.selectConversation(conversationStore.activeCid, true, true)
      }
      conversationStore.loadConversations(true).catch(e => {
        console.error('断线重连后会话列表静默刷新失败:', e)
      })
    }
    isFirstWsOpen = false
  })
  wsService.on('CLOSE', () => {
    appStore.isConnected = false
    appStore.loopRunning = false
  })

  // 判定是否是子会话。parentCid 为 null/undefined 或 0 代表主会话（本身无父智能体），非空合法 ID 代表子会话
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

  /**
   * 工具日志流内容的最大保留长度（字符），与引擎侧 StreamBufferHolder.TOOL_LOG_KEEP_LENGTH 口径一致。
   * 命令输出上限受后端保护阀约束（可达 200 万字符），前端仅保留尾部（结论通常在末尾），
   * 防止失控刷屏输出撑爆虚拟列表 DOM。
   */
  const TOOL_LOG_KEEP_LENGTH = 100000

  /**
   * 判定服务端下发的字段是否为「空」（null/undefined/空串）。
   * 用于消息更新时区分「服务端尚未产出该字段」与「服务端下发了真实内容」：
   * 前者需保留前端流式累积，后者须以后端为准覆盖。
   */
  const isBlankField = (value: any): boolean => {
    return value === null || value === undefined || value === ''
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

  // 监听大局会话整建制更新事件（全局广播，涵盖改名、状态监控、Token等）
  wsService.on('S2C_CONVERSATION_UPDATED', (event) => {
    const payload = event.payload
    if (!payload) return
    const cid = Number(event.cid ?? payload.id)
    const parentCid = event.parentCid ?? payload.parentCid

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

    // 同步更新会话列表中对应的那个（包括改名、更新时间、running 转圈监控、Token等）
    const currentSess = conversationStore.conversationList.find(s => s.id === cid)
    if (currentSess) {
      if (payload.title) currentSess.title = payload.title
      if (payload.updateTime || payload.updatedAt) currentSess.updateTime = payload.updateTime || payload.updatedAt
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

      // 动态折叠 D1/D5：新消息仅追加外露（USER 直接追加冻结前段；助手/工具追加外露不动折叠块）
      appendIncomingMessage(targetMessages, newMsg)

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

      // 动态折叠 D4：目标 id 落在折叠范围内的更新一律忽略，不更新不追加
      const target = targetMessages.find((m: any) => m.id === payload.id)
      const isFoldedTarget = isInFoldedRange(targetMessages, payload.id)
      if (!isFoldedTarget) {
        if (target) {
          // MESSAGE_UPDATE 载荷是后端按消息 ID 查库后的完整实体快照：非空字段即库中真值，一律以后端为准；
          // 仅当服务端该字段为空（流式期间正文尚未落库）时才保留本地流式累积，防止过程中内容被清空。
          // PENDING 为重试/重置信号（后端 resetAssistantMessage / resetUserMessageAndTruncateSubsequent），
          // 必须无条件覆盖（含清空），否则旧内容清不掉，新一轮内容会与旧内容拼接。
          const isPending = payload.status === 'PENDING'

          if (isPending) {
            // 重试/重置：无条件全量覆盖，保证服务端清空语义生效
            Object.assign(target, payload)
          } else {
            // 以字段为单位判定：服务端为空则剔除该字段以保留本地累积；非空则覆盖。
            // 工具消息前端零本地累积，其结果正文完全依赖此处落地（曾因"本地运行中就跳过 content"
            // 导致实时视图只有入参没有出参，折叠详情/刷新又正常）
            const patch = { ...payload }
            if (isBlankField(patch.content)) {
              delete patch.content
            }
            // thought 与 content 各自独立判定，避免其中一个为空时连带剥离另一个
            if (isBlankField(patch.thought)) {
              delete patch.thought
            }
            Object.assign(target, patch)
          }

          // D2 工具转 WAITING_APPROVAL：所在小组起外露，之前小组并入折叠块
          if (target.role === 'tool' && payload.status === 'WAITING_APPROVAL') {
            onToolWaitingApproval(targetMessages, payload.id)
          }
        } else {
          targetMessages.push(payload)
        }

        // D3 助手转终态
        if (payload.role === 'assistant' || payload.role === 'branch' || payload.role === 'compressed') {
          const m = targetMessages.find((m: any) => m.id === payload.id)
          if (m && (m.status === 'SUCCESS' || m.status === 'FAILED' || m.status === 'CANCELED')) {
            onAssistantTerminal(targetMessages, payload.id)
          }
        }
      }

      if (payload.role === 'assistant' && (payload.status === 'SUCCESS' || payload.status === 'FAILED' || payload.status === 'CANCELED')) {
        if (!isSub) {
          appStore.loopRunning = false
          conversationStore.loadConversations()
        }
      }
      if (isSub) {
        const sub = agentStore.getTargetAgent(Number(cid))
        if (sub) {
          if (payload.status === 'SUCCESS') {
            sub.status = 'success'
          } else if (payload.status === 'FAILED' || payload.status === 'CANCELED') {
            sub.status = 'failed'
          }
        }
      } else {
        if (payload.status === 'FAILED' || payload.status === 'CANCELED') {
          appStore.loopRunning = false
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
    const payload = event.payload as StreamChunkPayload
    const parentCid = event.parentCid
    const cid = event.cid

    const msgId = Number(payload.id)

    // 清空信号（后端透明重试重放前发出）：丢弃该消息已累积的本条流内容，随后重放内容从零累加。
    // 判定必须前置于下方的空文本过滤——清空信号本身不携带 text，否则会被静默跳过而继续累加致重复。
    // 此处刻意不创建新消息：清空只对有既有内容的消息有意义，找不到说明本就没有可清之物。
    if (payload.type === 'CLEAR') {
      if (!msgId) return
      const targetMessages = isSubConversation(parentCid)
        ? (agentStore.getTargetAgent(Number(cid))?.messages || [])
        : conversationStore.messages
      const target = targetMessages.find((m: any) => m.id === msgId)
      if (target) {
        if (isReasoning) {
          target.thought = ''
        } else {
          target.content = ''
        }
      }
      return
    }

    const chunkText = payload.text
    if (!msgId || !chunkText) return

    if (isSubConversation(parentCid)) {
      const sub = agentStore.getTargetAgent(Number(cid))
      if (sub) {
        let currentMsg = sub.messages.find((m: any) => m.id === msgId)
        if (!currentMsg) {
          currentMsg = sub.messages.find((m: any) => m.role === 'assistant' && (m.status === 'RUNNING' || m.status === 'PENDING'))
        }
        if (!currentMsg) {
          const newMsg: Message = {
            id: msgId,
            role: 'assistant',
            content: '',
            thought: '',
            status: 'RUNNING'
          }
          sub.messages.push(newMsg)
          currentMsg = newMsg
        }
        if (isReasoning) {
          currentMsg.thought = (currentMsg.thought || '') + chunkText
        } else {
          currentMsg.content += chunkText
        }
      }
      return
    }

    let currentMsg = conversationStore.messages.find(m => m.id === msgId)
    if (!currentMsg) {
      currentMsg = conversationStore.messages.find(m => m.role === 'assistant' && (m.status === 'RUNNING' || m.status === 'PENDING'))
    }
    if (!currentMsg) {
      currentMsg = {
        id: msgId,
        role: 'assistant' as const,
        content: '',
        thought: '',
        status: 'RUNNING' as const
      }
      conversationStore.messages.push(currentMsg)
    }

    if (currentMsg) {
      if (isReasoning) {
        currentMsg.thought = (currentMsg.thought || '') + chunkText
      } else {
        currentMsg.content += chunkText
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

  /**
   * 工具控制台日志流（command 类工具执行过程中由子进程 stdout 逐行产出）。
   * <p>
   * 与助手流的关键差异：终态时后端会把结果 JSON 全量写入 content（含完整 output），
   * 故此处仅在 RUNNING 期间做增量追加；一旦进入终态即停止追加，
   * 避免在结果 JSON 之后继续拼接日志（刷新后又消失，前后端不一致）。
   * </p>
   * <p>
   * 载荷 id 为工具消息 ID（与助手流统一模型），直接按 m.id 定位即可。
   * 截断口径与引擎侧缓存一致：仅保留尾部 10 万字符，防止命令失控刷屏撑爆虚拟列表 DOM。
   * </p>
   */
  const handleToolLogStream = (event: any) => {
    if (!shouldProcessEvent(event)) return
    const payload = event.payload as StreamChunkPayload
    const msgId = Number(payload.id)
    const text = payload.text
    if (!msgId || !text) return

    const targetMessages = isSubConversation(event.parentCid)
      ? (agentStore.getTargetAgent(Number(event.cid))?.messages || [])
      : conversationStore.messages

    const tool = targetMessages.find((m: any) => m.id === msgId)
    // 仅 RUNNING 期间追加：终态（含 PENDING/WAITING_APPROVAL）不接收，与后端终态清缓存口径对齐
    if (!tool || tool.status !== 'RUNNING') return

    const merged = (tool.content || '') + text
    tool.content = merged.length > TOOL_LOG_KEEP_LENGTH ? merged.slice(-TOOL_LOG_KEEP_LENGTH) : merged
  }
  wsService.on('S2C_TOOL_LOG_STREAM', handleToolLogStream)

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
        // 动态折叠 D6：普通消息保留 id <= targetId；FOLDED 块保留 maxId <= targetId（USER 不在折叠块内，无跨界）
        applyResetDeletion(sub.messages, targetId)
      }
    } else {
      if (cid === conversationStore.activeCid) {
        applyResetDeletion(conversationStore.messages, targetId)
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
  // 载荷字段一律「有值才覆盖」：后端存在部分广播（如仅变更密码时只带 passwordSet，
  // 其余字段为 null），若按"缺失即取默认值"赋值，会把用户已设置的其他项重置回默认值
  wsService.on('S2C_CONFIG_UPDATED', (event) => {
    const payload = event.payload
    if (!payload) return
    if (payload.newlineKey !== undefined) appStore.newlineKey = payload.newlineKey
    if (payload.httpLog !== undefined) appStore.httpLog = payload.httpLog
    if (payload.httpLogDays !== undefined) appStore.httpLogDays = payload.httpLogDays
    // 密码广播不携带密码值，仅同步"是否已设置"状态标记
    if (payload.passwordSet !== undefined) appStore.passwordSet = payload.passwordSet
    if (payload.minimalSkillMode !== undefined) appStore.minimalSkillMode = payload.minimalSkillMode
  })

  // 监听供应商配置更新事件，保障多客户端/多窗口实时同步
  wsService.on('S2C_PROVIDERS_UPDATED', (event) => {
    if (event.payload) {
      providerStore.providerList = event.payload
    } else {
      providerStore.loadProviders()
    }
  })

  // 监听 MCP 状态更新事件：MCP 服务异步启动完成或工具集变动时，服务端推送最新全量状态，直接替换看板数据
  wsService.on('S2C_MCP_UPDATED', (event) => {
    if (event.payload) {
      agentStore.mcpList = event.payload
    }
  })

  // 注：历史遗留的 S2C_CHAT_ERROR 与 S2C_PERMISSION_REQUEST 监听已删除——
  // 后端推送通道（mapToWsType 定向 + WebSocketBroadcast 广播枚举 + PONG）从未包含这两个类型，二者为死代码。
  // 对话异常经 S2C_MESSAGE_UPDATED 终态消息自愈展示；权限审批由 WAITING_APPROVAL 工具消息的就地面板（ToolGroupCard）承担。
})

onUnmounted(() => {
  wsService.close()
  wsService.clearAllCallbacks()
  if (gitTimer) {
    clearInterval(gitTimer)
  }
})
</script>
