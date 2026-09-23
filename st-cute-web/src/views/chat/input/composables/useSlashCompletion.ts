import { computed, nextTick, ref, watch, type Ref } from 'vue'
import { useAppStore } from '@/stores/app'
import { useConversationStore } from '@/stores/conversation'
import { getSlashListApi } from '@/api/slash'
import type { SlashGroupItem, SlashItem } from '@/types'

export function useSlashCompletion(options: {
  getTextareaEl: () => HTMLTextAreaElement | null | undefined
}) {
  const appStore = useAppStore()
  const conversationStore = useConversationStore()

  // 下拉是否弹出（光标前文本构成 /+关键词 形态时为 true）
  const slashVisible = ref(false)
  // 后端返回的原始分组数据（每次弹出实时拉取，不缓存）
  const slashGroups = ref<SlashGroupItem[]>([])
  // 当前过滤关键词（光标前文本中 / 与光标之间的输入片段）
  const slashKeyword = ref('')
  // 过滤后拍平的选项总数中当前高亮的索引（默认高亮第一项）
  const slashHighlightIndex = ref(0)
  // 请求中标记
  const slashLoading = ref(false)
  // 关闭豁免标记：选中回填或手动关闭后，光标前文本不再重新构成 / 形态前不再自动弹出，
  // 避免回填赋值触发 watch 导致「关闭又立即重开」的抖动；
  // 光标前文本离开 / 形态后自动复位，之后重新输入 / 可再次触发
  const slashDismissed = ref(false)
  // 全部选项元素引用表：拍平索引 -> DOM 元素
  const slashItemEls = new Map<number, HTMLElement>()

  /**
   * 收集下拉选项元素引用（v-for 动态 ref 回调）
   */
  const setSlashItemRef = (el: any, flatIndex: number) => {
    if (el) {
      slashItemEls.set(flatIndex, el as HTMLElement)
    }
  }

  /**
   * 键盘移动高亮后，将当前高亮项滚动到下拉可视范围内（不居中，仅贴边最小滚动）
   */
  const scrollHighlightedIntoView = () => {
    nextTick(() => {
      const el = slashItemEls.get(slashHighlightIndex.value)
      el?.scrollIntoView({ block: 'nearest' })
    })
  }

  /**
   * 将过滤后的分组数据渲染为带拍平索引的结构，供高亮与键盘选中定位使用
   */
  const slashRenderGroups = computed(() => {
    const keyword = slashKeyword.value.trim().toLowerCase()
    let flatIndex = 0
    return slashGroups.value
      .map(group => {
        const filteredItems = (group.items || []).filter(item =>
          !keyword || item.name.toLowerCase().includes(keyword)
        )
        const startFlatIndex = flatIndex
        flatIndex += filteredItems.length
        return { group: group.group, items: filteredItems, startFlatIndex }
      })
      .filter(group => group.items.length > 0)
  })

  /**
   * 过滤后的选项总条数（空列表时 Enter 完全无效的判定依据）
   */
  const slashFilteredCount = computed(() => {
    return slashRenderGroups.value.reduce((sum, group) => sum + group.items.length, 0)
  })

  /**
   * 关闭下拉并复位状态
   */
  const closeSlashDropdown = () => {
    slashVisible.value = false
    slashGroups.value = []
    slashKeyword.value = ''
    slashHighlightIndex.value = 0
    slashLoading.value = false
    // 清空选项元素引用表，避免下次弹出残留旧 DOM 引用
    slashItemEls.clear()
  }

  /**
   * 选中补全项：基于光标区间的局部替换回填（不整体覆盖输入，
   * 保留 / 之前与关键词之后的其他正文内容），关闭下拉，光标移至
   * 插入内容末尾并保持聚焦
   */
  const applySlashItem = (item: SlashItem) => {
    const textarea = options.getTextareaEl()
    const text = appStore.userInput || ''
    // 与触发 watch 同源的定位规则：以当前光标位置为终点，取光标前文本做 /关键词 正则匹配，
    // 命中则只替换「/ 起到光标为止」的片段；不依赖 slashKeyword 状态，
    // 避免下拉打开期间 ←/→ 移动光标（不触发 watch）导致的状态与光标漂移
    if (textarea) {
      const end = textarea.selectionStart ?? text.length
      const m = text.slice(0, end).match(/^\/([a-zA-Z0-9_-]*)$/)
      if (m) {
        const start = end - m[1].length
        // 先打上豁免标记再回填：回填赋值会触发输入 watch，
        // 若不打标记会因光标前文本仍为 / 形态而被误判为重新触发（关闭后立即重开的抖动）
        slashDismissed.value = true
        // 仅替换光标前的 /关键词 片段，保留 / 之前与光标之后的其他正文
        appStore.userInput = text.slice(0, start - 1) + `/${item.name} ` + text.slice(end)
        nextTick(() => {
          // 光标落在插入内容末尾（/{name}+空格 之后），保持聚焦
          const cursor = start - 1 + item.name.length + 2
          textarea.selectionStart = textarea.selectionEnd = cursor
          textarea.focus()
        })
        closeSlashDropdown()
        return
      }
    }
    // 兜底：无法定位光标或光标前不构成 / 形态时退回整体回填（正常场景不会走到这里）
    slashDismissed.value = true
    appStore.userInput = `/${item.name} `
    closeSlashDropdown()
    nextTick(() => {
      const ta = options.getTextareaEl()
      if (ta) {
        ta.selectionStart = ta.selectionEnd = (appStore.userInput || '').length
        ta.focus()
      }
    })
  }

  const handleSlashItemClick = (item: SlashItem) => {
    applySlashItem(item)
  }

  /**
   * 拉取 slash 分组列表（每次弹出实时请求，不缓存）
   */
  const fetchSlashList = async () => {
    const cid = conversationStore.activeCid
    if (!cid) return
    slashLoading.value = true
    try {
      const res = await getSlashListApi(cid)
      slashGroups.value = Array.isArray(res) ? res : []
    } catch (e) {
      console.error('拉取 slash 补全列表失败:', e)
      slashGroups.value = []
    } finally {
      slashLoading.value = false
    }
  }

  /**
   * 根据当前高亮索引在渲染分组中定位对应选项
   */
  const resolveHighlightedItem = (): SlashItem | null => {
    for (const group of slashRenderGroups.value) {
      const offset = slashHighlightIndex.value - group.startFlatIndex
      if (offset >= 0 && offset < group.items.length) {
        return group.items[offset]
      }
    }
    return null
  }

  /**
   * slash 下拉打开期间的键盘接管处理
   * 设计约束：接管期间绝不触发消息发送；带修饰键的 Enter 原样放行换行；IME 组词期放行
   */
  const handleSlashKeydown = (e: KeyboardEvent) => {
    // 中文输入法组词期间的 Enter 为「确认候选词上屏」，不接管，原样放行
    if (e.isComposing || e.keyCode === 229) {
      return
    }

    // 高亮索引归一化：数据变化（过滤结果减少）后索引可能越界，钳制到有效范围
    if (slashHighlightIndex.value >= slashFilteredCount.value) {
      slashHighlightIndex.value = Math.max(0, slashFilteredCount.value - 1)
    }

    switch (e.key) {
      case 'ArrowDown':
        if (slashFilteredCount.value > 0) {
          e.preventDefault()
          slashHighlightIndex.value = (slashHighlightIndex.value + 1) % slashFilteredCount.value
          scrollHighlightedIntoView()
        }
        break
      case 'ArrowUp':
        if (slashFilteredCount.value > 0) {
          e.preventDefault()
          slashHighlightIndex.value = (slashHighlightIndex.value - 1 + slashFilteredCount.value) % slashFilteredCount.value
          scrollHighlightedIntoView()
        }
        break
      case 'Escape':
        e.preventDefault()
        // 手动关闭同样进入豁免期：同一 / 开头文本生命周期内不再自动弹出，
        // 输入不再以 / 开头（删掉斜杠/清空）后自动复位
        slashDismissed.value = true
        closeSlashDropdown()
        break
      case 'Enter':
        // 仅裸 Enter 用于选中补全；带修饰键的 Enter 原样放行给输入框换行
        if (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) {
          return
        }
        e.preventDefault()
        e.stopPropagation()
        if (slashFilteredCount.value > 0) {
          const item = resolveHighlightedItem()
          if (item) {
            applySlashItem(item)
          }
        }
        // 列表为空时：什么都不做（既不选中也不发送）
        break
      default:
        break
    }
  }

  /**
   * 监听输入变化，判定 slash 触发与关闭（光标感知版）
   */
  watch(
    () => appStore.userInput,
    (newVal) => {
      const textarea = options.getTextareaEl()
      // 取光标位置：取不到时退回文本末尾（与旧版整体匹配行为对齐）
      const cursorPos = textarea ? (textarea.selectionStart ?? (newVal || '').length) : (newVal || '').length
      const textBeforeCursor = (newVal || '').slice(0, cursorPos)
      // 光标前文本须严格匹配 /关键词 形态，/ 前不能再有其他字符
      const match = textBeforeCursor.match(/^\/([a-zA-Z0-9_-]*)$/)

      if (!match) {
        // 光标前文本不构成 / 触发形态（含清空、光标已越过斜杠区）时关闭下拉，并复位豁免标记，
        // 使之后重新输入 / 能再次正常触发
        if (slashVisible.value) {
          closeSlashDropdown()
        }
        slashDismissed.value = false
        return
      }

      // 提取光标前 / 与光标之间的关键词用于过滤
      slashKeyword.value = match[1]

      // 处于豁免期（选中回填/手动关闭后同一文本生命周期）时不自动弹出
      if (slashDismissed.value) {
        return
      }

      if (!slashVisible.value) {
        // 未连接服务端时不弹出补全下拉：选项需从后端实时拉取，断线期间请求必然失败并弹错误提示；
        // 连接恢复后用户重新输入 / 可正常触发
        if (!appStore.isConnected) return
        // 触发瞬间：弹出下拉并实时拉取后端列表
        slashVisible.value = true
        slashHighlightIndex.value = 0
        fetchSlashList()
      }
    }
  )

  return {
    slashVisible,
    slashLoading,
    slashRenderGroups,
    slashHighlightIndex,
    slashDismissed,
    setSlashItemRef,
    handleSlashItemClick,
    closeSlashDropdown,
    handleSlashKeydown
  }
}
