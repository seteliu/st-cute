import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { wsService } from '@/services/websocket'
import {
  getConversations,
  getConversationMessages,
  deleteConversationById,
  batchDeleteConversationsApi,
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
import { useGitStore } from './git'
import { Message, Conversation, StagedFile } from '@/types'

export const useConversationStore = defineStore('conversation', () => {
  const conversationList = ref<Conversation[]>([])
  const activeCid = ref<number | null>(null)
  const messages = ref<Message[]>([])
  const truncated = ref(false)
  const isMessageLoading = ref(false)
  const isMessageSpinning = ref(false)
  // 静默加载模式的延迟转圈定时器句柄（仅断线重连强刷场景使用）
  let spinnerDelayTimer: ReturnType<typeof setTimeout> | null = null
  const inputTokens = ref(0)
  const outputTokens = ref(0)
  const cachedTokens = ref(0)

  // 按会话吸附的输入框草稿：切换会话时交割（旧会话暂存、新会话取回），
  // 无记录的会话视为空草稿，不预建条目避免无谓增长
  const draftMap = ref<Record<number, string>>({})
  // 按会话吸附的暂存附件：条目内持有 File 对象与预览 ObjectURL 引用，
  // 切换会话仅隐藏不销毁，切回即原样恢复；仅发送成功与会话删除时才真正释放
  const stagedFilesMap = ref<Record<number, StagedFile[]>>({})

  const appStore = useAppStore()
  const projectStore = useProjectStore()

  /**
   * 会话切换时的草稿交割：把当前输入框内容记到旧会话名下，再把新会话草稿回填输入框。
   * 由 ChatInput 在 activeCid 变更前调用（oldCid 交割来源、newCid 取回目标）
   */
  const switchDraftContext = (oldCid: number | null, newCid: number) => {
    if (oldCid !== null) {
      const prev = appStore.userInput || ''
      if (prev) {
        draftMap.value[oldCid] = prev
      } else {
        delete draftMap.value[oldCid]
      }
    }
    appStore.userInput = draftMap.value[newCid] || ''
  }

  /**
   * 释放指定会话的暂存附件：逐个回收图片预览 ObjectURL 并删除 map 条目。
   * 触发时机：该会话发送成功、该会话被删除（单个/批量）、全局清空环境
   */
  const disposeStagedFiles = (cid: number) => {
    const list = stagedFilesMap.value[cid]
    if (list) {
      list.forEach(item => {
        if (item.previewUrl) URL.revokeObjectURL(item.previewUrl)
      })
      delete stagedFilesMap.value[cid]
    }
  }

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
    // 清空环境的同时清掉全部按会话草稿与暂存附件，防止脏数据滞留
    draftMap.value = {}
    Object.keys(stagedFilesMap.value).forEach(key => {
      disposeStagedFiles(Number(key))
    })
    appStore.userInput = ''
  }

  // 静默加载失败后的自动重试参数：最多 2 次，间隔 1.5 秒。
  // 重连瞬间的网络抖动是消息加载失败的主因，幂等 GET 重试零副作用；
  // 重试期间保持静默（不弹全局错误），全部耗尽才降级为一次性提示
  const SILENT_RETRY_TIMES = 2
  const SILENT_RETRY_INTERVAL = 1500
  const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

  // 加载会话列表
  // silent: 静默模式（断线重连强刷场景）——HTTP 层不弹全局错误提示，失败仅记 console
  const loadConversations = async (silent = false) => {
    try {
      const data = await getConversations(silent)
      // 过滤出绑定了有效项目的会话，防止历史孤儿脏数据干扰
      // （workspaceId 为项目 ID 字符串，与项目列表 id 数值比对需弱等转换）
      const validConversations = data.filter(s =>
        s.workspaceId && projectStore.projectList.some(p => String(p.id) === String(s.workspaceId))
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
        // 默认选中时，优先选择当前活跃项目下的主会话
        const mainConversations = validConversations.filter(s => !s.parentCid)
        const activeProjectConv = mainConversations.find(s =>
          projectStore.activeProjectId !== null && String(s.workspaceId) === String(projectStore.activeProjectId)
        )
        if (activeProjectConv) {
          await selectConversation(activeProjectConv.id)
        } else if (mainConversations.length > 0) {
          await selectConversation(mainConversations[0].id)
        } else if (projectStore.projectList.length > 0 && projectStore.activeProjectId !== null) {
          // 只有当前项目确实存在且 activeProjectId 有效时，才自动创建会话
          // 避免删除项目后立即创建孤立会话
          await createConversation(projectStore.activeProjectId || undefined)
        }
      }
    } catch (e) {
      console.error('加载历史会话失败:', e)
    }
  }

  // 选择会话
  // silent: 静默加载模式（断线重连后强刷场景使用）。转圈不立即出现，
  // 延迟 1 秒后仍未加载完成才降级显示加载动画，快速重连则全程无感知
  const selectConversation = async (id: number, force = false, silent = false) => {
    if (!force && activeCid.value === id) return
    // 入口统一摘除上一次静默加载的延迟转圈定时器，防止竞态：
    // 静默等待期内用户手动切换会话时，旧定时器若未清理会让新会话莫名转圈
    if (spinnerDelayTimer !== null) {
      clearTimeout(spinnerDelayTimer)
      spinnerDelayTimer = null
    }
    activeCid.value = id
    wsService.setCid(id)

    if (silent) {
      // 静默模式：1 秒内拉完则全程不转圈，超时才降级显示加载动画
      spinnerDelayTimer = setTimeout(() => {
        isMessageLoading.value = true
        isMessageSpinning.value = true
        spinnerDelayTimer = null
      }, 1000)
    } else {
      isMessageLoading.value = true
      isMessageSpinning.value = true
    }

    // 联动切换当前选中的项目并初始化 Token 用量展示
    // （workspaceId 为项目 ID 字符串，changeActiveProject 需要数值 ID，做安全转换）
    const sess = conversationList.value.find(s => s.id === id)
    if (sess) {
      const projectIdNum = sess.workspaceId ? Number(sess.workspaceId) : NaN
      if (!Number.isNaN(projectIdNum)) {
        projectStore.changeActiveProject(projectIdNum)
      }
      inputTokens.value = sess.inputTokens || 0
      outputTokens.value = sess.outputTokens || 0
      cachedTokens.value = sess.cachedTokens || 0
    } else {
      inputTokens.value = 0
      outputTokens.value = 0
      cachedTokens.value = 0
    }

    let lastError: any = null
    try {
      // 失败自动重试：重连瞬间或弱网下的失败多为瞬时抖动，幂等 GET 重试零副作用；
      // 重试期间保持静默不弹全局错误，全部耗尽才在 finally 之后降级为一次性提示
      for (let attempt = 0; attempt <= SILENT_RETRY_TIMES; attempt++) {
        if (attempt > 0) {
          await sleep(SILENT_RETRY_INTERVAL)
          // 等待期间用户已切走：不再对旧会话做无意义的重试（新会话会自行触发加载）
          if (activeCid.value !== id) return
        }
        try {
          const res = await getConversationMessages(id, undefined, silent)
          // 回包守卫：加载期间用户已切走到其他会话则整体中止，
          // 避免旧会话慢响应（含重试链拉长的窗口）污染新会话已就绪的消息列表
          if (activeCid.value !== id) return
          truncated.value = res.truncated || false
          const list = res.messages || []

          messages.value = list.map((msg) => {
            const roleLower = msg.role ? (msg.role.toLowerCase() as any) : 'assistant'
            return {
              ...msg,
              role: roleLower
            }
          })
          lastError = null
          break
        } catch (e) {
          lastError = e
          console.error(`加载历史消息与会话状态失败(第 ${attempt + 1} 次尝试):`, e)
        }
      }
    } catch (e) {
      // 外层兜底（重试循环自身异常，理论上不会进入）
      lastError = e
      console.error('加载历史消息与会话状态失败:', e)
    } finally {
      // 先摘除尚未触发的静默延迟定时器（快速重连场景：请求已在 1 秒内完成，转圈从未出现过）
      if (spinnerDelayTimer !== null) {
        clearTimeout(spinnerDelayTimer)
        spinnerDelayTimer = null
      }
      // 转圈收起守卫：加载期间用户已切走时，新会话正在展示自己的加载态，
      // 旧会话的慢响应（含重试链）不得提前掐灭新会话的转圈标志
      if (activeCid.value === id) {
        isMessageLoading.value = false
        // 150ms 后停止转轮背景，保持首屏灵敏度
        setTimeout(() => {
          isMessageSpinning.value = false
        }, 150)
      }
    }

    // 重试耗尽后的一次性降级提示：此刻距重连已过数秒、转圈早已收起，不再抢跑首屏观感；
    // 且能走到这一步说明网络大概率确实不通，提示是必要的用户感知而非打扰。
    // 静默（重连强刷）与普通（手动切换会话）模式文案区分，便于用户区分场景定位问题；
    // 已切走到其他会话则不再打扰（新会话会自行加载并反馈自己的结果）
    if (lastError !== null && activeCid.value === id) {
      const hint = silent
        ? '重连后恢复会话消息失败，请检查网络或刷新重试'
        : '加载历史消息与会话状态失败，请检查网络或后端连接'
      ;(window as any).$message?.error(hint)
    }

        // 环境资产静默加载：消息列表就绪后执行，独立 try 且不阻塞本函数返回，全程不触发转圈。
        // 顺序：先拉环境上下文 info（权限模式、Token 用量、技能等）；git 分支与变动列表优先级最低，info 完成后再拉。
    // 守卫：回包时若已切走到其他会话则整体中止，避免旧会话慢响应污染新会话状态（新会话会自行触发加载）
    const loadEnvAssets = async () => {
      try {
        const envInfo = await getContextInfoApi(id, silent)
        if (activeCid.value !== id) return
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

        const gitStore = useGitStore()
        await gitStore.fetchBranches(true)
      } catch (e) {
        console.error('加载会话环境上下文信息失败:', e)
        // 静默化处理：环境资产（技能/权限模式/Token/git）非关键路径，失败不打扰用户，
        // 后续会话切换或 git 轮询会自然重拉自愈；此处保留 error 日志便于排查
      }
    }
    loadEnvAssets()
  }

  // 新建会话
  const createConversation = async (projectId?: number) => {
    const pId = projectId || projectStore.activeProjectId
    if (pId === null || pId === undefined) {
      // 如果没有任何项目，则不能新建会话
      return
    }

    // 供应商列表为空时不允许创建会话（后端不自动填充，绑定完全由前端传值）
    const providerStore = useProviderStore()
    const list = providerStore.providerList
    if (!list || list.length === 0) {
      if ((window as any).$message) {
        ;(window as any).$message.warning('暂无可用的大模型供应商，请先在“系统设置”中配置后再创建会话。')
      } else {
        console.warn('暂无可用的大模型供应商，无法创建会话')
      }
      return
    }

    // 解析默认绑定供应商：优先取当前活跃会话绑定的供应商（且在列表中仍有效），
    // 否则取列表第一个（全新会话取下拉第一个）
    const activeSess = activeCid.value !== null
      ? conversationList.value.find(s => s.id === activeCid.value)
      : null
    const recentBound = activeSess && list.find(p => p.group === activeSess.providerGroup && p.modelName === activeSess.providerModelName)
      ? activeSess
      : null
    const target = recentBound
      ? { group: recentBound.providerGroup as string, modelName: recentBound.providerModelName as string }
      : { group: list[0].group, modelName: list[0].modelName || '' }

    // workspaceId 为项目 ID 的字符串形态（Coding 宿主语义，由后端 WorkspaceResolver 解释）
    const payload: Partial<Conversation> = {
      title: '新会话',
      workspaceId: String(pId),
      providerGroup: target.group,
      providerModelName: target.modelName
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
      // 会话已删除，同步清理其草稿与暂存附件记录，防止滞留造成内存与状态残留
      delete draftMap.value[id]
      disposeStagedFiles(id)

      if (activeCid.value === id) {
        activeCid.value = null
        // 优先在当前选中项目的会话中选择
        const currentProjectId = projectStore.activeProjectId
        const projectConversations = currentProjectId
          ? conversationList.value.filter(s => String(s.workspaceId) === String(currentProjectId))
          : []
        
        if (projectConversations.length > 0) {
          // 当前项目还有会话，选中第一个
          await selectConversation(projectConversations[0].id)
        } else if (currentProjectId === null && conversationList.value.length > 0) {
          // 无活跃项目时的兜底：无项目上下文才有资格跳到其他会话
          await selectConversation(conversationList.value[0].id)
        } else if (currentProjectId !== null) {
          // 当前项目的会话已删光但项目还在：留在当前项目下新建会话，不跳其他项目的会话（与刷新后 loadConversations 行为一致）
          clearContext()
          createConversation(currentProjectId || undefined)
        } else {
          // 没有项目了，彻底清空
          clearContext()
        }
      }
    } catch (e) {
      console.error('删除会话失败:', e)
    }
  }

  // 批量删除会话
  const handleBatchDelete = async (ids: number[]) => {
    if (!ids || ids.length === 0) return
    try {
      await batchDeleteConversationsApi(ids)
      conversationList.value = conversationList.value.filter(s => !ids.includes(s.id))
      // 批量删除的会话同步清理草稿与暂存附件记录
      ids.forEach(mid => {
        delete draftMap.value[mid]
        disposeStagedFiles(mid)
      })

      if (activeCid.value !== null && ids.includes(activeCid.value)) {
        activeCid.value = null
        // 优先在当前选中项目的会话中选择
        const currentProjectId = projectStore.activeProjectId
        const projectConversations = currentProjectId
          ? conversationList.value.filter(s => String(s.workspaceId) === String(currentProjectId))
          : []
        
        if (projectConversations.length > 0) {
          // 当前项目还有会话，选中第一个
          await selectConversation(projectConversations[0].id)
        } else if (currentProjectId === null && conversationList.value.length > 0) {
          // 无活跃项目时的兜底：无项目上下文才有资格跳到其他会话
          await selectConversation(conversationList.value[0].id)
        } else if (currentProjectId !== null) {
          // 当前项目的会话已删光但项目还在：留在当前项目下新建会话，不跳其他项目的会话（与刷新后 loadConversations 行为一致）
          clearContext()
          createConversation(currentProjectId || undefined)
        } else {
          // 没有项目了，彻底清空
          clearContext()
        }
      }
    } catch (e) {
      console.error('批量删除会话失败:', e)
      throw e
    }
  }

  // 发送消息
  const sendUserMsg = async (attachments?: string) => {
    const text = appStore.userInput.trim()
    if (!text || appStore.loopRunning) return

    // WebSocket 未连接时禁止发送：消息虽走 HTTP 通道，但回显与流式内容全靠 WS 推送，
    // 断线期间发送会导致"发了没反应"的半死状态，直接静默拦截
    if (!appStore.isConnected) return

    const id = activeCid.value
    if (id === null) return

    // 发送前置校验：必须保证有确定有效的供应商（后端不做兜底，匹配不上直接报错拒绝）。
    // ① 会话绑定的供应商（group+modelName）在列表中精确命中 → 直接发送；
    // ② 绑定失效或未绑定，但列表非空 → 先把降级目标（列表第一个）持久化绑定到会话再发送；
    // ③ 列表为空 → 拦截发送并提示
    const sess = conversationList.value.find(s => s.id === id)
    const providerStore = useProviderStore()
    const list = providerStore.providerList
    if (!list || list.length === 0) {
      if ((window as any).$message) {
        ;(window as any).$message.warning('暂无可用的大模型供应商，请先在左侧“系统设置”中配置并添加。')
      } else {
        console.warn('暂无可用的大模型供应商，当前无法发起对话。')
      }
      return
    }

    let pGroup: string
    let pModel: string
    const bound = sess?.providerGroup
      ? list.find(p => p.group === sess.providerGroup && p.modelName === sess.providerModelName)
      : undefined
    if (bound) {
      // 绑定有效，沿用会话已绑定的供应商
      pGroup = bound.group
      pModel = bound.modelName
    } else {
      // 绑定失效或未绑定：降级取列表第一个并持久化写回（接口会联动更新子会话）
      const eff = list[0]
      pGroup = eff.group
      pModel = eff.modelName || ''
      try {
        await updateConversationProviderApi(id, pGroup, pModel)
      } catch (e) {
        console.error('发送前绑定降级供应商失败:', e)
      }
      if (sess) {
        sess.providerGroup = pGroup
        sess.providerModelName = pModel
      }
      if ((window as any).$message) {
        ;(window as any).$message.warning(`原供应商已失效，本次发送将使用 ${pGroup}/${pModel}`)
      }
    }

    appStore.currentIteration = 0
    
    const oldInput = appStore.userInput
    // 发送前暂存本会话草稿：发送成功后清除，失败时保留供输入框原样恢复
    draftMap.value[id] = oldInput
    appStore.userInput = ''
    appStore.loopRunning = true
    
    sendMessageApi(id, { text, attachments }).then(() => {
      // 成功发送后无需手动在此处更新，等待 WebSocket 推送 S2C_MESSAGE_CREATED 事件后自动追加。
      // 发送成功：彻底释放本会话暂存附件（回收预览 ObjectURL 与 map 记录）并清除草稿暂存；
      // 发送失败：两者均原样保留，供用户重试
      disposeStagedFiles(id)
      delete draftMap.value[id]
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
    renameConversation,
    handleBatchDelete,
    draftMap,
    stagedFilesMap,
    switchDraftContext
  }
})

// 启用 Pinia store 热更新：dev 热替换时复用原 store 实例，避免新旧实例并存导致组件状态分裂、刷新链断裂
if (import.meta.hot) {
  acceptHMRUpdate(useConversationStore, import.meta.hot)
}

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
