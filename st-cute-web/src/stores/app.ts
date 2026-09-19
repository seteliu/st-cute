import { defineStore } from 'pinia'
import { Message } from '@/types'
import { ref, computed } from 'vue'
import { wsService } from '@/services/websocket'
import { getConfigApi, saveConfigApi } from '@/api/config'
import { updateConversationConfigApi, approveConversationPermissionApi, cancelConversationApi, getMessageDetailApi } from '@/api/conversation'
import { useConversationStore } from './conversation'
import { useUserStore } from './user'
import { setLanguage, Language, t } from '@/i18n'

export const useAppStore = defineStore('app', () => {
  const isConnected = ref(false)
  const isInitialized = ref(false)
  const userInput = ref('')
  const loopRunning = ref(false)

  // 左右边栏完全折叠控制
  const leftSiderCollapsed = ref(false)
  const rightSiderCollapsed = ref(false)
  
  // 左右侧边栏宽度控制，默认分别为 280 和 291
  const leftSiderWidth = ref(280)
  const rightSiderWidth = ref(291)

  // 【预留】聊天消息头像展示开关：控制对话消息是否展示角色头像。
  // 当前无任何设置入口修改此值，仅作后续扩展预留，默认关闭（false）。
  // 关闭后消息布局中头像占位与间隙会自动收缩（见 MessageItem.vue / MessageListFlow.vue）。
  const showMessageAvatar = ref(false)

  // 系统基础配置项
  const language = ref<Language>('zh-CN')
  const newlineKey = ref<'enter' | 'alt+enter'>('enter')
  const httpLog = ref(false)
  const httpLogDays = ref(7)
  // 本地输入态：仅承载"本次要设置的新密码"，保存前本地转 SHA-256 摘要发送；后端不回传既有密码
  const password = ref('')
  // 服务端密码状态：是否已设置安全访问密码（替代旧的明文回显）
  const passwordSet = ref(false)
  const maxViewHistoryLimit = ref(2000)
  const pathSandboxEnabled = ref(true)
  // 极简 Skill 模式：开启后技能清单不注入系统提示词以节省 Token，仅按需触发加载
  const minimalSkillMode = ref(false)
  
  // 权限安全配置
  const permissionMode = ref('READ_ONLY')
  
  // 迭代进度
  const currentIteration = ref(0)
  
  // 权限审批弹窗相关
  const showPermissionModal = ref(false)
  const alwaysAllowChecked = ref(false)
  const permissionPatternMode = ref<'exact' | 'prefix' | 'all'>('prefix')
  const currentPermissionReq = ref<{
    id: string
    toolName: string
    arguments: string
    subAgent?: any
  } | null>(null)
  const isEditingArgs = ref(false)
  const editedArgumentsJson = ref('')
  
  // 原始运行日志抽屉
  const showLogDrawer = ref(false)
  const rawLogContent = ref('')
  const currentViewToolCall = ref<any>(null)
  const currentViewMessage = ref<Message | null>(null)

  // 思考过程详情抽屉
  const showThoughtDrawer = ref(false)
  const thoughtDetailContent = ref('')
  const currentViewThoughtMessageId = ref<number | string | null>(null)

  // 快捷选项列表
  const permissionModeOptions = computed(() => [
    { label: t('sider.modeReadOnly'), value: 'READ_ONLY' },
    { label: t('sider.modeSmart'), value: 'SMART_APPROVAL' },
    { label: t('sider.modeAllAllow'), value: 'ALL_ALLOW' }
  ])

  // 提取命令的第一个单词
  const commandPrefixPattern = computed(() => {
    if (!currentPermissionReq.value) return ''
    try {
      const args = JSON.parse(currentPermissionReq.value.arguments)
      const cmd = args.command
      if (cmd) {
        const trimmed = cmd.trim()
        const firstWord = trimmed.split(/\s+/)[0]
        return firstWord ? `${firstWord} *` : '*'
      }
    } catch (e) {
      // ignore
    }
    return '*'
  })

  // 提取精确命令
  const commandExactPattern = computed(() => {
    if (!currentPermissionReq.value) return ''
    try {
      const args = JSON.parse(currentPermissionReq.value.arguments)
      return args.command || '*'
    } catch (e) {
      // ignore
    }
    return '*'
  })

  // 提取文件夹路径通配符
  const fileDirPattern = computed(() => {
    if (!currentPermissionReq.value) return ''
    try {
      const args = JSON.parse(currentPermissionReq.value.arguments)
      const path = args.path
      if (path) {
        const lastSlashIdx = path.lastIndexOf('/')
        if (lastSlashIdx !== -1) {
          return `${path.substring(0, lastSlashIdx)}/**`
        }
      }
    } catch (e) {
      // ignore
    }
    return '*'
  })

  // 提取精确文件路径
  const fileExactPattern = computed(() => {
    if (!currentPermissionReq.value) return ''
    try {
      const args = JSON.parse(currentPermissionReq.value.arguments)
      return args.path || '*'
    } catch (e) {
      // ignore
    }
    return '*'
  })

  // 计算匹配的 glob
  const calculatedPattern = computed(() => {
    if (!currentPermissionReq.value) return ''
    try {
      const isCmd = currentPermissionReq.value.toolName === 'RunCommandTool' || currentPermissionReq.value.toolName === 'execute_command'
      if (isCmd) {
        if (permissionPatternMode.value === 'exact') {
          return commandExactPattern.value
        } else if (permissionPatternMode.value === 'prefix') {
          return commandPrefixPattern.value
        } else {
          return '*'
        }
      } else {
        if (permissionPatternMode.value === 'exact') {
          return fileExactPattern.value
        } else if (permissionPatternMode.value === 'prefix') {
          return fileDirPattern.value
        } else {
          return '*'
        }
      }
    } catch (e) {
      // ignore
    }
    return '*'
  })

  // 格式化 JSON
  const formatArgumentsJson = (argsStr: string | undefined) => {
    if (!argsStr) return ''
    try {
      const parsed = JSON.parse(argsStr)
      return JSON.stringify(parsed, null, 2)
    } catch (e) {
      return argsStr
    }
  }

  // 改变权限安全模式
  const handlePermissionModeChange = (val: string) => {
    permissionMode.value = val
    const conversationStore = useConversationStore()
    if (conversationStore.activeCid !== null) {
      updateConversationConfigApi(conversationStore.activeCid, { permissionMode: val }).then(() => {
        const activeId = conversationStore.activeCid
        // 联动更新直接子会话的 permissionMode
        conversationStore.conversationList.forEach(s => {
          if (s.parentCid === activeId) {
            s.permissionMode = val
          }
        })
      }).catch(err => {
        console.error('更新权限模式失败:', err)
      })
    }
  }

  // 提交审批决定
  const handlePermissionDecision = (decision: 'ALLOW' | 'DENY') => {
    if (!currentPermissionReq.value) return

    let customArgOverride: string | undefined = undefined
    if (decision === 'ALLOW' && isEditingArgs.value) {
      // 编辑过参数时必须提供合法 JSON：解析失败直接拦截提交并提示，
      // 禁止把乱七八糟的原文静默透传给后端执行层
      try {
        const parsed = JSON.parse(editedArgumentsJson.value)
        customArgOverride = JSON.stringify(parsed)
      } catch (e) {
        if ((window as any).$message) {
          ;(window as any).$message.error('参数不是合法的 JSON，请修正后再允许执行')
        } else {
          console.error('参数 JSON 解析失败，已拦截提交:', e)
        }
        return
      }
    }

    const conversationStore = useConversationStore()
    // 如果是子智能体的审批，定向使用其自身的 cid，否则使用主会话的 activeCid
    const targetCid = currentPermissionReq.value.subAgent
      ? Number(currentPermissionReq.value.subAgent.cid)
      : conversationStore.activeCid

    if (targetCid !== null && targetCid !== undefined) {
      approveConversationPermissionApi(targetCid, {
        id: currentPermissionReq.value.id,
        decision: decision,
        alwaysAllow: decision === 'ALLOW' && alwaysAllowChecked.value,
        toolName: currentPermissionReq.value.toolName,
        contentPattern: calculatedPattern.value,
        customArgOverride: customArgOverride
      }).then(() => {
        // 成功后，同步清除该子智能体的局部挂起状态
        if (currentPermissionReq.value?.subAgent) {
          currentPermissionReq.value.subAgent.pendingPermissionReq = undefined
        }
      }).catch(err => {
        console.error('发送审批决定失败:', err)
      })
    }

    showPermissionModal.value = false
    currentPermissionReq.value = null
    alwaysAllowChecked.value = false
    isEditingArgs.value = false
    permissionPatternMode.value = 'prefix'
  }

  // 查看原始工具日志 (升级为根据物理消息 ID 详情查询)
  const showRawLog = async (messageId: number) => {
    rawLogContent.value = '正在加载日志...'
    showLogDrawer.value = true
    currentViewToolCall.value = null
    currentViewMessage.value = null
    try {
      const detail = await getMessageDetailApi(messageId)
      if (detail) {
        currentViewMessage.value = detail
        currentViewToolCall.value = {
          name: detail.toolName || '',
          arguments: detail.toolArguments || '{}'
        } as any
        rawLogContent.value = detail.content || '无日志内容'
      } else {
        rawLogContent.value = '未找到对应的日志记录'
      }
    } catch (e: any) {
      rawLogContent.value = '加载日志发生错误: ' + e.message
    }
  }

  // 打开思考详情抽屉 (支持传递静态内容与消息 ID 进行流式响应绑定)
  const openThoughtDetail = (content: string, messageId?: number | string | null) => {
    thoughtDetailContent.value = content || ''
    currentViewThoughtMessageId.value = messageId || null
    showThoughtDrawer.value = true
  }

  const cancelLoop = () => {
    const conversationStore = useConversationStore()
    if (conversationStore.activeCid !== null) {
      cancelConversationApi(conversationStore.activeCid).catch(err => {
        console.error('取消会话失败:', err)
      })
    }
    loopRunning.value = false
  }

  // 加载系统配置
  const loadBasicConfig = async () => {
    try {
      const data = await getConfigApi()
      if (data.language) {
        language.value = data.language as Language
        setLanguage(language.value)
      }
      newlineKey.value = data.newlineKey || 'enter'
      httpLog.value = data.httpLog || false
      httpLogDays.value = data.httpLogDays !== undefined ? data.httpLogDays : 7
      // 密码状态标记：后端不再回传明文/摘要，本地输入框仅承载"本次要设置的新密码"
      passwordSet.value = data.passwordSet || false
      password.value = ''
      maxViewHistoryLimit.value = data.maxViewHistoryLimit || 2000
      pathSandboxEnabled.value = data.pathSandboxEnabled !== undefined ? data.pathSandboxEnabled : true
      minimalSkillMode.value = data.minimalSkillMode || false
    } catch (e) {
      console.error(t('settings.loadFailed'), e)
    }
  }

  // 保存系统配置
  const saveBasicConfig = async () => {
    try {
      setLanguage(language.value)
      await saveConfigApi({
        language: language.value,
        newlineKey: newlineKey.value,
        httpLog: httpLog.value,
        httpLogDays: httpLogDays.value,
        password: password.value,
        // 保持既有交互习惯：已设置过密码且本地输入框被清空时，保存即关闭密码保护；
        // 未设置过密码时清空框就是"保持未启用"，两种情况均无需下发清除标记
        passwordClear: passwordSet.value && !password.value,
        pathSandboxEnabled: pathSandboxEnabled.value,
        minimalSkillMode: minimalSkillMode.value
      })
      // 保存成功后清空本地输入态，回到"仅展示 passwordSet 状态"的干净基线
      password.value = ''
      if ((window as any).$message) {
        ;(window as any).$message.success(t('settings.saveSuccess'))
      }
      // 立即触发用户信息校验，保障配置密码后立刻强制鉴权
      const userStore = useUserStore()
      await userStore.fetchUserInfo()
    } catch (e) {
      console.error(t('settings.saveFailed'), e)
    }
  }

  return {
    isConnected,
    isInitialized,
    userInput,
    loopRunning,
    
    leftSiderCollapsed,
    rightSiderCollapsed,
    leftSiderWidth,
    rightSiderWidth,
    showMessageAvatar,
    
    language,
    newlineKey,
    httpLog,
    httpLogDays,
    password,
    passwordSet,
    maxViewHistoryLimit,
    pathSandboxEnabled,
    minimalSkillMode,
    
    permissionMode,
    currentPermissionReq,
    isEditingArgs,
    editedArgumentsJson,
    calculatedPattern,
    commandExactPattern,
    commandPrefixPattern,
    fileExactPattern,
    fileDirPattern,
    
    showLogDrawer,
    rawLogContent,
    currentViewToolCall,
    currentViewMessage,
    
    permissionModeOptions,
    formatArgumentsJson,
    handlePermissionModeChange,
    handlePermissionDecision,
    showRawLog,
    showThoughtDrawer,
    thoughtDetailContent,
    currentViewThoughtMessageId,
    openThoughtDetail,
    cancelLoop,
    loadBasicConfig,
    saveBasicConfig,
    currentIteration,
    showPermissionModal,
    alwaysAllowChecked,
    permissionPatternMode
  }

})
