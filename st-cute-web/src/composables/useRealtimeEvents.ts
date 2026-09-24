import { onMounted, onUnmounted } from 'vue'
import { wsService } from '@/services/websocket'
import { StreamChunkPayload, Conversation, Message } from '@/types'
import { WS_EVENTS, LOCAL_EVENTS } from '@/constants/ws-events'
import { normalizeMessage } from '@/utils/message'
import {
  appendIncomingMessage,
  isInFoldedRange,
  onAssistantTerminal,
  onToolWaitingApproval,
  applyResetDeletion
} from '@/utils/foldEngine'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { useProviderStore } from '@/stores/provider'
import { useAgentStore } from '@/stores/agent'
import { useProjectStore } from '@/stores/project'
import { useGitStore } from '@/stores/git'
import { currentTheme, switchTheme } from '@/styles/theme'

/** 会话列表重拉防抖窗口（毫秒）：运行期间 CONVERSATION_UPDATED 随每条消息更新高频广播，直接全量拉取会风暴式打接口 */
const LIST_RELOAD_DEBOUNCE_MS = 1500

/**
 * 实时事件编排 composable（原 Home.vue 内联的 WS 事件处理整体抽离）。
 *
 * 职责边界：
 * - 只做「WS 协议帧 → store 状态」的翻译与落库，不含任何 DOM/布局逻辑
 * - 与 Home.vue 的生命周期绑定：onMounted 注册、onUnmounted 注销（HMR/重挂载安全）
 * - git 轮询属环境资产刷新（非 WS 协议），一并收口于此
 *
 * 抽离动机：原实现 440 行内联在 onMounted 中，不可复用、不可测试，
 * 与后端 RuntimeEventListenerWebSocket（同职责）对位。
 */
export function useRealtimeEvents() {
  const appStore = useAppStore()
  const conversationStore = useConversationStore()
  const providerStore = useProviderStore()
  const agentStore = useAgentStore()
  const projectStore = useProjectStore()
  const gitStore = useGitStore()

  let gitTimer: ReturnType<typeof setInterval> | null = null
  // 防抖句柄：会话列表重拉合并器（高频 CONVERSATION_UPDATED 期间只打一次接口）
  let listReloadTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 防抖版会话列表重拉：窗口期内多次触发合并为一次请求。
   * 会话运行期间该事件随 Token/迭代高频广播（每条消息更新都伴随），
   * 前置的本地条目增量更新已保证列表数据实时性，这里仅为对齐数据库真值兜底
   */
  const debouncedReloadConversations = () => {
    if (listReloadTimer !== null) {
      clearTimeout(listReloadTimer)
    }
    listReloadTimer = setTimeout(() => {
      listReloadTimer = null
      conversationStore.loadConversations().catch(e => {
        console.error('防抖重拉会话列表失败:', e)
      })
    }, LIST_RELOAD_DEBOUNCE_MS)
  }

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

  /** 按事件目标（主/子会话）解析消息落点数组（子会话落在对应子代理的消息缓冲）。
   *  getTargetAgent 找不到必新建，永不返回空——此处不做空数组兜底，
   *  让未来签名变化时类型直接暴露约束而非静默吞推送 */
  const resolveTargetMessages = (parentCid: any, cid: any): Message[] => {
    return isSubConversation(parentCid)
      ? agentStore.getTargetAgent(Number(cid)).messages
      : conversationStore.messages
  }

  const registerListeners = () => {
    // ---------------- 连接生命周期 ----------------
    let isFirstWsOpen = true
    wsService.on(LOCAL_EVENTS.OPEN, () => {
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
    wsService.on(LOCAL_EVENTS.CLOSE, () => {
      appStore.isConnected = false
    })

    // ---------------- 会话域事件 ----------------
    wsService.on(WS_EVENTS.CONVERSATION_CREATED, (event) => {
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

    wsService.on(WS_EVENTS.CONVERSATION_UPDATED, (event) => {
      const payload: Partial<Conversation> = event.payload
      if (!payload) return
      const cid = Number(event.cid ?? payload.id)
      const parentCid = event.parentCid ?? payload.parentCid

      if (isSubConversation(parentCid)) {
        // 说明是子会话的更新：只要其父会话 ID 是当前活动主会话，就同步其最新的状态更新
        if (Number(parentCid) === conversationStore.activeCid) {
          const sub = agentStore.getTargetAgent(cid)
          if (sub) {
            sub.currentIteration = payload.iterationCount || 0
            sub.inputTokens = payload.inputTokens || 0
            sub.outputTokens = payload.outputTokens || 0
            sub.cachedTokens = payload.cachedTokens || 0
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
          if (payload.loopRunning !== undefined) {
            appStore.loopRunning = payload.loopRunning === 1
          }
          appStore.applyPermissionMode(payload.permissionMode || 'STRICT_APPROVAL')
          appStore.currentIteration = payload.iterationCount || 0

          conversationStore.inputTokens = payload.inputTokens || 0
          conversationStore.outputTokens = payload.outputTokens || 0
          conversationStore.cachedTokens = payload.cachedTokens || 0

          // 防抖重拉会话列表，以同步最新的并发子会话卡片等信息
          //（本地条目已增量更新，此处仅兜底对齐数据库真值，高频广播下合并为一次请求）
          debouncedReloadConversations()
        }
      }

      // 同步更新会话列表中对应的那个（包括改名、更新时间、running 转圈监控、Token等）
      const currentSess = conversationStore.conversationList.find(s => s.id === cid)
      if (currentSess) {
        if (payload.title) currentSess.title = payload.title
        if (payload.updateTime) currentSess.updateTime = payload.updateTime
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

    wsService.on(WS_EVENTS.CONVERSATION_DELETED, (event) => {
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

    // ---------------- 消息域事件 ----------------
    wsService.on(WS_EVENTS.MESSAGE_CREATED, (event) => {
      if (!shouldProcessEvent(event)) return
      const payload = event.payload
      const parentCid = event.parentCid
      const cid = event.cid

      if (payload && payload.id) {
        // role 规范化收口：后端大写枚举统一转小写（utils/message.ts 为全局唯一适配点）
        const newMsg = normalizeMessage(payload)

        const isSub = isSubConversation(parentCid)
        const targetMessages = resolveTargetMessages(parentCid, cid)

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

    wsService.on(WS_EVENTS.MESSAGE_UPDATED, (event) => {
      if (!shouldProcessEvent(event)) return
      const payload = event.payload
      const parentCid = event.parentCid
      const cid = event.cid

      if (payload && payload.id) {
        const normalized = normalizeMessage(payload)

        const isSub = isSubConversation(parentCid)
        const targetMessages = resolveTargetMessages(parentCid, cid)

        // 动态折叠 D4：目标 id 落在折叠范围内的更新一律忽略，不更新不追加
        const target = targetMessages.find((m: any) => m.id === normalized.id)
        const isFoldedTarget = isInFoldedRange(targetMessages, normalized.id)
        if (!isFoldedTarget) {
          if (target) {
            // MESSAGE_UPDATE 载荷是后端按消息 ID 查库后的完整实体快照：非空字段即库中真值，一律以后端为准；
            // 仅当服务端该字段为空（流式期间正文尚未落库）时才保留本地流式累积，防止过程中内容被清空。
            // PENDING 为重试/重置信号（后端 resetAssistantMessage / resetUserMessageAndTruncateSubsequent），
            // 必须无条件覆盖（含清空），否则旧内容清不掉，新一轮内容会与旧内容拼接。
            const isPending = normalized.status === 'PENDING'

            if (isPending) {
              // 重试/重置：无条件全量覆盖，保证服务端清空语义生效
              Object.assign(target, normalized)
            } else {
              // 以字段为单位判定：服务端为空则剔除该字段以保留本地累积；非空则覆盖。
              // 工具消息前端零本地累积，其结果正文完全依赖此处落地（曾因"本地运行中就跳过 content"
              // 导致实时视图只有入参没有出参，折叠详情/刷新又正常）
              const nonEmptyPatch = { ...normalized } as Partial<Message>
              if (isBlankField(nonEmptyPatch.content)) {
                delete nonEmptyPatch.content
              }
              // thought 与 content 各自独立判定，避免其中一个为空时连带剥离另一个
              if (isBlankField(nonEmptyPatch.thought)) {
                delete nonEmptyPatch.thought
              }
              Object.assign(target, nonEmptyPatch)
            }

            // D2 工具转 WAITING_APPROVAL：所在小组起外露，之前小组并入折叠块
            if (target.role === 'tool' && normalized.status === 'WAITING_APPROVAL') {
              onToolWaitingApproval(targetMessages, normalized.id)
            }
          } else {
            targetMessages.push(normalized)
          }

          // D3 助手转终态
          if (normalized.role === 'assistant' || normalized.role === 'branch' || normalized.role === 'compressed') {
            const m = targetMessages.find((m: any) => m.id === normalized.id)
            if (m && (m.status === 'SUCCESS' || m.status === 'FAILED' || m.status === 'CANCELED')) {
              onAssistantTerminal(targetMessages, normalized.id)
            }
          }
        }

        if (isSub) {
          const sub = agentStore.getTargetAgent(Number(cid))
          if (sub) {
            if (normalized.status === 'SUCCESS') {
              sub.status = 'success'
            } else if (normalized.status === 'FAILED' || normalized.status === 'CANCELED') {
              sub.status = 'failed'
            }
          }
        }
      }
    })

    wsService.on(WS_EVENTS.MESSAGE_DELETED, (event) => {
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
        }
      }
    })

    // ---------------- 流式事件 ----------------
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
        const targetMessages = resolveTargetMessages(parentCid, cid)
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

      const targetMessages = resolveTargetMessages(parentCid, cid)
      let currentMsg = targetMessages.find((m: any) => m.id === msgId)
      if (!currentMsg) {
        currentMsg = targetMessages.find((m: any) => m.role === 'assistant' && (m.status === 'RUNNING' || m.status === 'PENDING'))
      }
      if (!currentMsg) {
        const newMsg: Message = {
          id: msgId,
          role: 'assistant',
          content: '',
          thought: '',
          status: 'RUNNING'
        }
        targetMessages.push(newMsg)
        currentMsg = newMsg
      }

      if (currentMsg) {
        if (isReasoning) {
          currentMsg.thought = (currentMsg.thought || '') + chunkText
        } else {
          currentMsg.content += chunkText
        }
      }
    }

    // 大模型响应流式渲染 (思维过程与正文回答拆分)
    wsService.on(WS_EVENTS.THINKING_STREAM, (event) => {
      handleChatStream(event, true)
    })
    wsService.on(WS_EVENTS.CONTENT_STREAM, (event) => {
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

      const targetMessages = resolveTargetMessages(event.parentCid, event.cid)
      const tool = targetMessages.find((m: any) => m.id === msgId)
      // 仅 RUNNING 期间追加：终态（含 PENDING/WAITING_APPROVAL）不接收，与后端终态清缓存口径对齐
      if (!tool || tool.status !== 'RUNNING') return

      const merged = (tool.content || '') + text
      tool.content = merged.length > TOOL_LOG_KEEP_LENGTH ? merged.slice(-TOOL_LOG_KEEP_LENGTH) : merged
    }
    wsService.on(WS_EVENTS.TOOL_LOG_STREAM, handleToolLogStream)

    // S2C_HOOK_EVENT 无契约：后端 HookService.sendHookEventWs 仅记日志、不推送 WS 帧。
    // 工具消息的 hooks 字段随 MESSAGE_UPDATED 全量快照自然落地；
    // 若后端未来恢复推送，在 constants/ws-events.ts 登记常量并在此补监听即可。

    // ---------------- 配置与资产广播 ----------------
    wsService.on(WS_EVENTS.PROJECT_CREATED, async (event) => {
      await projectStore.loadProjects()
    })

    wsService.on(WS_EVENTS.PROJECT_DELETED, async (event) => {
      const deletedId = Number(event.payload)
      if (!deletedId) return

      if (projectStore.activeProjectId === deletedId) {
        projectStore.activeProjectId = null
      }
      await projectStore.loadProjects()
      await conversationStore.loadConversations()
    })

    // 系统配置更新：载荷字段一律「有值才覆盖」：后端存在部分广播（如仅变更密码时只带 passwordSet，
    // 其余字段为 null），若按"缺失即取默认值"赋值，会把用户已设置的其他项重置回默认值
    wsService.on(WS_EVENTS.CONFIG_UPDATED, (event) => {
      const payload = event.payload
      if (!payload) return
      if (payload.newlineKey !== undefined) appStore.newlineKey = payload.newlineKey
      if (payload.httpLog !== undefined) appStore.httpLog = payload.httpLog
      if (payload.httpLogDays !== undefined) appStore.httpLogDays = payload.httpLogDays
      if (payload.httpLogIncludeResponse !== undefined) appStore.httpLogIncludeResponse = payload.httpLogIncludeResponse
      // 密码广播不携带密码值，仅同步"是否已设置"状态标记
      if (payload.passwordSet !== undefined) appStore.passwordSet = payload.passwordSet
      if (payload.minimalSkillMode !== undefined) appStore.minimalSkillMode = payload.minimalSkillMode
      if (payload.theme !== undefined && (payload.theme === 'dark' || payload.theme === 'light')) {
        appStore.theme = payload.theme
        if (currentTheme.value !== payload.theme) {
          switchTheme(payload.theme)
        }
      }
    })

    wsService.on(WS_EVENTS.PROVIDERS_UPDATED, (event) => {
      if (event.payload) {
        providerStore.providerList = event.payload
      } else {
        providerStore.loadProviders()
      }
    })

    // MCP 状态更新：MCP 服务异步启动完成或工具集变动时，服务端推送最新全量状态，直接替换看板数据
    wsService.on(WS_EVENTS.MCP_UPDATED, (event) => {
      if (event.payload) {
        agentStore.mcpList = event.payload
      }
    })

    // 注：历史遗留的 S2C_CHAT_ERROR 与 S2C_PERMISSION_REQUEST 监听已删除——
    // 后端推送通道（mapToWsType 定向 + WebSocketBroadcast 广播枚举 + PONG）从未包含这两个类型，二者为死代码。
    // 对话异常经 S2C_MESSAGE_UPDATED 终态消息自愈展示；权限审批由 WAITING_APPROVAL 工具消息的就地面板（ToolGroupCard）承担。
  }

  onMounted(() => {
    // 防御性清空先前残留的 WS 回调监听器，彻底解决 HMR 或重复 mount 导致的流式内容叠加 Bug
    wsService.clearAllCallbacks()

    // 初始化轮询（首次不主动拉取：此刻 activeCid 尚未就绪，fetchBranches 只会空转清态；
    // 真正的首次拉取由后续 selectConversation 的 loadEnvAssets 链触发）
    gitTimer = window.setInterval(() => {
      gitStore.fetchBranches(true)
    }, 10000)

    registerListeners()

    // 开启 WebSocket 长连接
    wsService.connect()
  })

  onUnmounted(() => {
    wsService.close()
    wsService.clearAllCallbacks()
    if (gitTimer) {
      clearInterval(gitTimer)
      gitTimer = null
    }
    if (listReloadTimer !== null) {
      clearTimeout(listReloadTimer)
      listReloadTimer = null
    }
  })
}
