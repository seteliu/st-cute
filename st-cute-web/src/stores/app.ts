import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { Message } from '@/types'
import { wsService } from '@/services/websocket'
import { getConfigApi, saveConfigApi, savePasswordApi, clearPasswordApi } from '@/api/config'
import { updateConversationConfigApi, cancelConversationApi } from '@/api/conversation'
import { useConversationStore } from './conversation'
import { useUserStore } from './user'
import { setLanguage, Language, t } from '@/i18n'
import { currentTheme, switchTheme } from '@/styles/theme'
import { ThemeName } from '@/styles/themeVars'

/**
 * 安全访问码复杂度策略：trim 后 8~32 位、须同时包含英文字母与数字、仅允许常见密码字符。
 * 与后端 PasswordPolicy 保持同一口径（后端因仅收到摘要，字符集与组成只能在前端校验；
 * 长度则由后端随摘要附带的 passwordLength 做兜底），保存前本地先校验
 */
const PASSWORD_MIN_LENGTH = 8
const PASSWORD_MAX_LENGTH = 32
const PASSWORD_ALLOWED_RE = /^[A-Za-z0-9 !"#\$%&'\(\)\*\+,\-.\/:;<=>\?@\[\\\]\^_`\{\|}~]+$/

/**
 * 校验安全访问码是否满足复杂度策略，不满足时返回具体原因；满足返回 null
 */
const validatePasswordPolicy = (raw: string): string | null => {
  const trimmed = raw.trim()
  if (trimmed.length < PASSWORD_MIN_LENGTH) {
    return t('settings.passwordTooShort')
  }
  if (trimmed.length > PASSWORD_MAX_LENGTH) {
    return t('settings.passwordTooLong')
  }
  if (!/[A-Za-z]/.test(trimmed) || !/[0-9]/.test(trimmed)) {
    return t('settings.passwordNeedLetterAndDigit')
  }
  if (!PASSWORD_ALLOWED_RE.test(trimmed)) {
    return t('settings.passwordInvalidChar')
  }
  return null
}

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
  const theme = ref<ThemeName>(currentTheme.value)
  const newlineKey = ref<'enter' | 'alt+enter'>('enter')
  const httpLog = ref(false)
  const httpLogDays = ref(7)
  // 是否记录响应部分（含 SSE 流式响应全文）：关闭后仅记录请求报文与异常，避免流式日志把文件冲爆
  const httpLogIncludeResponse = ref(true)
  // 服务端密码状态：是否已设置安全访问密码（密码值本身全链路不回传，仅此布尔标记）
  const passwordSet = ref(false)
  const maxViewHistoryLimit = ref(2000)
  const pathSandboxEnabled = ref(true)
  // 极简 Skill 模式：开启后技能清单不注入系统提示词以节省 Token，仅按需触发加载
  const minimalSkillMode = ref(false)

  // 权限安全配置
  const permissionMode = ref('STRICT_APPROVAL')

  // 迭代进度
  const currentIteration = ref(0)

  // 原始运行日志抽屉
  const showLogDrawer = ref(false)
  // 当前查看的工具消息 ID：抽屉内容由该 ID 从消息列表实时取，支持流式日志跟随
  const currentViewToolMessageId = ref<number | null>(null)
  // 兜底数据源：消息不在任何响应式列表时（如折叠详情弹窗的范围查询结果）由调用方直接传入对象引用。
  // 存引用而非拷贝，故对象自身仍可保持响应式（折叠详情中的工具均为终态，内容不会再变）
  const currentViewToolFallback = ref<Message | null>(null)

  // 思考过程详情抽屉
  const showThoughtDrawer = ref(false)
  const thoughtDetailContent = ref('')
  const currentViewThoughtMessageId = ref<number | string | null>(null)

  // 快捷选项列表
  const permissionModeOptions = computed(() => [
    { label: t('sider.modeStrictApproval'), value: 'STRICT_APPROVAL' },
    { label: t('sider.modeRelaxedApproval'), value: 'RELAXED_APPROVAL' },
    { label: t('sider.modeAllAllow'), value: 'ALL_ALLOW' }
  ])

  // 应用权限模式（统一收口点）：后端返回值若匹配不到任何选项（如 DB 存量旧值 READ_ONLY / SMART_APPROVAL），
  // 回退选中第一档（严格审批），保证下拉框选中值恒与选项列表精确匹配、不出现原始串显示
  const applyPermissionMode = (val: string) => {
    permissionMode.value = permissionModeOptions.value.some(option => option.value === val)
      ? val
      : permissionModeOptions.value[0].value
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

  /**
   * 查看工具运行日志：记录消息 ID（及可选兜底对象）并打开抽屉。
   * <p>
   * 抽屉内容优先由 RawLogDrawer 以该 ID 从响应式消息列表实时取值（参考 ThoughtDetailDrawer 范式），
   * 故无需请求接口即可跟随日志流刷新。
   * </p>
   * <p>
   * 但折叠详情弹窗的消息来自范围查询（folded=false + minId/maxId）落于组件局部状态，
   * 不在任何响应式列表中，此时必须由调用方传入消息对象兜底，否则抽屉会空白。
   * </p>
   *
   * @param messageId 工具消息 ID
   * @param fallback  兜底消息对象（消息不在响应式列表时传入，如折叠详情）
   */
  const showRawLog = (messageId: number, fallback?: Message | null) => {
    currentViewToolMessageId.value = messageId
    currentViewToolFallback.value = fallback || null
    showLogDrawer.value = true
  }

  /**
   * 打开思考详情抽屉：绑定目标消息 ID（可选）并记录原文快照兜底。
   * <p>
   * 抽屉内容优先由 ThoughtDetailDrawer 以该 ID 从响应式消息列表实时取值，故主会话/子代理场景
   * 无需传内容即可跟随思考流刷新。
   * </p>
   * <p>
   * 但折叠详情弹窗的消息来自范围查询（folded=false + minId/maxId）落于组件局部状态，
   * 折叠时已从响应式列表物理删除，按 ID 查列表必然落空；此时必须由调用方传入
   * <b>原始 thought 全文</b>作为快照兜底，否则抽屉会空白。严禁传清洗成单行的精简文本，
   * 否则兜底内容丢失换行与缩进，思考全文会堆叠成一行。
   * </p>
   *
   * @param content   兜底快照内容（原始思考全文，保留换行与缩进）
   * @param messageId 目标消息 ID（命中响应式列表时优先按 ID 实时取值）
   */
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
      if (data.theme && (data.theme === 'dark' || data.theme === 'light')) {
        theme.value = data.theme
        if (currentTheme.value !== data.theme) {
          switchTheme(data.theme)
        } else {
          try {
            localStorage.setItem('st-cute-theme', data.theme)
          } catch (e) {}
        }
      }
      newlineKey.value = data.newlineKey || 'enter'
      httpLog.value = data.httpLog || false
      httpLogDays.value = data.httpLogDays !== undefined ? data.httpLogDays : 7
      httpLogIncludeResponse.value = data.httpLogIncludeResponse !== undefined ? data.httpLogIncludeResponse : true
      // 仅取"是否已设置"布尔标记：密码值全链路不回传，界面据此渲染 设置 / 修改+清除 按钮
      passwordSet.value = data.passwordSet || false
      maxViewHistoryLimit.value = data.maxViewHistoryLimit || 2000
      pathSandboxEnabled.value = data.pathSandboxEnabled !== undefined ? data.pathSandboxEnabled : true
      minimalSkillMode.value = data.minimalSkillMode || false
    } catch (e) {
      console.error(t('settings.loadFailed'), e)
    }
  }

  // 保存系统配置（不含密码：密码走 savePassword / clearPassword 专用接口）
  const saveBasicConfig = async () => {
    try {
      setLanguage(language.value)
      await saveConfigApi({
        language: language.value,
        theme: theme.value,
        newlineKey: newlineKey.value,
        httpLog: httpLog.value,
        httpLogDays: httpLogDays.value,
        httpLogIncludeResponse: httpLogIncludeResponse.value,
        pathSandboxEnabled: pathSandboxEnabled.value,
        minimalSkillMode: minimalSkillMode.value
      })
      if ((window as any).$message) {
        ;(window as any).$message.success(t('settings.saveSuccess'))
      }
    } catch (e) {
      console.error(t('settings.saveFailed'), e)
    }
  }

  /**
   * 设置 / 修改安全访问密码。
   * <p>
   * 仅在弹窗中现输现提：本地先做复杂度校验（与后端 PasswordPolicy 同口径），
   * 通过后转 SHA-256 摘要走专用接口。原文不经过网络，也不与其它设置同批提交，
   * 从根本上避免浏览器自动填充污染密码值。
   * </p>
   *
   * @param rawPassword 用户输入的密码原文
   * @returns 是否保存成功
   */
  const savePassword = async (rawPassword: string): Promise<boolean> => {
    const policyError = validatePasswordPolicy(rawPassword)
    if (policyError) {
      if ((window as any).$message) {
        ;(window as any).$message.warning(policyError)
      }
      return false
    }
    try {
      await savePasswordApi(rawPassword)
      passwordSet.value = true
      if ((window as any).$message) {
        ;(window as any).$message.success(t('settings.passwordSaveSuccess'))
      }
      // 密码变更后立即校验用户信息：已配置密码则立刻强制鉴权
      const userStore = useUserStore()
      await userStore.fetchUserInfo()
      return true
    } catch (e) {
      console.error(t('settings.saveFailed'), e)
      return false
    }
  }

  /**
   * 清除安全访问密码（调用方需先行二次确认）。
   * <p>清除后系统回到未启用密码保护的状态：仅本机来源可访问。</p>
   *
   * @returns 是否清除成功
   */
  const clearPassword = async (): Promise<boolean> => {
    try {
      await clearPasswordApi()
      passwordSet.value = false
      if ((window as any).$message) {
        ;(window as any).$message.success(t('settings.passwordClearSuccess'))
      }
      // 清除后立即校验用户信息：系统可能随即收起免密通道（如仅允许本机来源），
      // 由服务端裁决当前连接是否仍具备访问资格，不通过则统一走 401 跳登录
      const userStore = useUserStore()
      await userStore.fetchUserInfo()
      return true
    } catch (e) {
      console.error(t('settings.saveFailed'), e)
      return false
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
    theme,
    newlineKey,
    httpLog,
    httpLogDays,
    httpLogIncludeResponse,
    passwordSet,
    maxViewHistoryLimit,
    pathSandboxEnabled,
    minimalSkillMode,
    
    permissionMode,
    
    showLogDrawer,
    currentViewToolMessageId,
    currentViewToolFallback,
    
    permissionModeOptions,
    applyPermissionMode,
    handlePermissionModeChange,
    showRawLog,
    showThoughtDrawer,
    thoughtDetailContent,
    currentViewThoughtMessageId,
    openThoughtDetail,
    cancelLoop,
    loadBasicConfig,
    saveBasicConfig,
    savePassword,
    clearPassword,
    currentIteration
  }

})

// 启用 Pinia store 热更新：dev 热替换时复用原 store 实例，避免新旧实例并存导致组件状态分裂、刷新链断裂
if (import.meta.hot) {
  acceptHMRUpdate(useAppStore, import.meta.hot)
}
