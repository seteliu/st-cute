/**
 * 【折叠视图共享引擎 - 主会话与子会话复用】
 *
 * 职责：
 * 1. buildRenderItems：将消息列表（含 FOLDED 虚拟消息）组装为渲染序列（工具嵌套/孤儿组/空助手过滤）
 * 2. 折叠视图增量维护：WS 事件到达时对列表做增量折叠，保证动态结果与刷新后端列表接口一致，
 *    并及时丢弃被折叠明细防止内存膨胀
 *
 * 【折叠算法 - 与后端严格对齐】
 * 对齐锚：后端 st-cute-core .../message/MessageServiceImpl.java -> buildFoldedView()
 * 修改本算法任一规则时，必须同步修改后端对齐锚实现，禁止单侧变更！
 *
 * R1 预处理：SYSTEM 跳过；TOOL 按 parentMessageId 并入父助手成原子小组（助手+其工具整体，永不拆开折叠）；
 *    空助手（无 content + 无 thought + SUCCESS + 名下无 TOOL 消息）跳过——
 *    纯工具调用助手（名下有 TOOL）不在跳过之列；无父孤儿工具独立成组，同样为原子小组
 * R2 分段：按 USER 消息切段，段内独立折叠；BRANCH/COMPRESSED 不切段，按助手角色参与终态判定
 * R3 待审批：首个含 WAITING_APPROVAL 的小组（助手自身或其任一工具待审批）起全部外露，之前的小组折叠
 * R4 终态：最后一条终态助手（SUCCESS/FAILED/CANCELED）小组外露，之前的小组折叠；段内无终态助手则全段外露
 *
 * 动态增量规则（与后端刷新语义严格对齐，核心不变量：每段 = [USER, FOLDED(已完成中间步骤), 最后终态助手小组外露, 尾部非终态外露]）：
 * D1 助手/工具以 PENDING/RUNNING 出现 → 仅追加外露，不动折叠块（此刻最后终态助手仍是上一个外露小组）
 * D2 工具转 WAITING_APPROVAL → 它之前的小组（含原外露的最后终态助手）并入折叠块，它所在小组起外露
 * D3 助手转终态 → 把折叠块之后、自己之前的所有消息（上一个外露终态小组）并入折叠块，自己成为新外露终态
 * D4 更新目标 id 落在某折叠块 [minId, maxId] 内 → 忽略（不可破坏折叠结构）
 * D5 新 USER 消息 → 直接 push 外露，前段冻结
 * D6 reset DELETED → 普通消息保留 id <= targetId，FOLDED 块保留 maxId <= targetId（USER 不在折叠块内，无跨界）
 * D7 retry → 不动折叠块，终态后自然收敛（瞬态差异可接受）
 */
import { Message } from '@/types'

/** 渲染序列条目（主会话 ChatContainer / 子会话 SubAgentDrawer 共用） */
export type RenderItem =
  | { type: 'message'; data: Message; tools?: Message[] }
  | { type: 'tool_group'; parentMessageId: number | string; tools: Message[] }
  | { type: 'folded'; folded: Message }
  | { type: 'truncated_tip' }

/** 折叠明细异常结构化条目（与后端 MessageVo.ErrorDetail 对齐） */
export interface FoldErrorDetail {
  kind: 'tool' | 'assistant'
  toolName?: string
}

/** 判定是否为助手角色（ASSISTANT/BRANCH/COMPRESSED，R4 终态判定口径） */
const isAssistantRole = (msg: Message): boolean =>
  msg.role === 'assistant' || msg.role === 'branch' || msg.role === 'compressed'

/** R1："五无"助手判定（无 content + 无 thought + SUCCESS + 名下无 TOOL），纯工具调用助手不在此列 */
const isBlankAssistant = (msg: Message, hasTools: boolean): boolean => {
  if (msg.role !== 'assistant') return false
  if (msg.status !== 'SUCCESS') return false
  if (msg.content || msg.thought) return false
  return !hasTools
}

/**
 * 【R1 静态组装】将原始消息列表组装为渲染序列。
 * 输入已含后端 FOLDED 虚拟消息时原样保留为折叠块；主会话与子会话共用。
 * 注意：FOLDED 块必须保持其在原列表中的位置——两段式循环时 FOLDED 先进占位数组，
 * 与普通消息统一在第二轮按原始顺序输出，严禁第一轮直接 push 导致折叠块全部堆到列表顶部。
 */
export const buildRenderItems = (messages: Message[]): RenderItem[] => {
  const result: RenderItem[] = []
  const toolMap = new Map<string | number, Message[]>()
  // 顺序占位数组：记录非工具消息的原始顺序与是否为 FOLDED 块
  const ordered: Array<{ folded: boolean; msg: Message }> = []

  // ---------- R1 预处理：拆分普通消息与工具消息，保持原始顺序 ----------
  for (const msg of messages) {
    if (msg.role === 'folded') {
      ordered.push({ folded: true, msg })
    } else if (msg.role === 'tool') {
      const pId = msg.parentMessageId || 'orphan_tools'
      if (!toolMap.has(pId)) toolMap.set(pId, [])
      toolMap.get(pId)!.push(msg)
    } else {
      ordered.push({ folded: false, msg })
    }
  }

  // 组装：SYSTEM 跳过；TOOL 注入父消息实现嵌套；五无助手过滤；FOLDED 原样保留
  for (const entry of ordered) {
    if (entry.folded) {
      result.push({ type: 'folded', folded: entry.msg })
      continue
    }
    const msg = entry.msg
    if (msg.role === 'system') continue
    const msgTools = toolMap.get(msg.id) || []
    const hasTools = msgTools.length > 0
    if (isBlankAssistant(msg, hasTools)) continue
    result.push({ type: 'message', data: msg, tools: hasTools ? msgTools : undefined })
  }

  // 兜底渲染没有任何父消息的孤儿工具消息
  if (toolMap.has('orphan_tools')) {
    result.push({ type: 'tool_group', parentMessageId: 'orphan_tools', tools: toolMap.get('orphan_tools')! })
  }

  return result
}

/**
 * 定位消息所属的段（R2：以 USER 为界）。返回 [segStartIdx, segEndIdx)（左闭右开）。
 * 从 idx 向前找最近的 USER 作为段头（找不到则 0）；向后找下一个 USER 作为段尾（找不到则数组末尾）。
 */
const locateSegment = (messages: Message[], idx: number): [number, number] => {
  let start = 0
  for (let i = idx; i >= 0; i--) {
    if (messages[i].role === 'user') { start = i; break }
  }
  let end = messages.length
  for (let i = idx + 1; i < messages.length; i++) {
    if (messages[i].role === 'user') { end = i; break }
  }
  return [start, end]
}

/** D4：判定 id 是否落在某个 FOLDED 块的 [minId, maxId] 闭区间内（迟到事件忽略判定） */
export const isInFoldedRange = (messages: Message[], id: number): boolean => {
  for (const m of messages) {
    if (m.role === 'folded' && m.foldedMinId !== undefined && m.foldedMaxId !== undefined) {
      if (id >= m.foldedMinId && id <= m.foldedMaxId) return true
    }
  }
  return false
}

/**
 * 定位消息所在小组的助手头下标：消息自身是助手/USER 则返回自身下标；是工具则向前找父助手；
 * 找不到父助手返回 -1（孤儿工具组）。
 */
const findGroupHeadIdx = (messages: Message[], segStart: number, idx: number): number => {
  const msg = messages[idx]
  if (msg.role !== 'tool') return idx
  if (msg.parentMessageId) {
    for (let i = idx - 1; i >= segStart; i--) {
      if (messages[i].id === msg.parentMessageId) return i
    }
  }
  return -1
}

/**
 * 【D2/D3 折叠时机】把段内 FOLDED 块之后、anchorIdx 之前的已完成小组并入折叠块。
 * anchorIdx 指向触发折叠的小组头（待审批工具的父助手，或转终态的助手自身），anchor 小组本身外露。
 * 段内尚无 FOLDED 块时在收集区间处新建一个。被并入的明细从列表物理删除（内存不膨胀的关键）。
 */
const foldUpTo = (messages: Message[], anchorIdx: number): void => {
  if (anchorIdx <= 0) return
  const [segStart] = locateSegment(messages, anchorIdx)

  // 找段内最后一个 FOLDED 块（动态演化下每段至多一个，位于 USER 之后）
  let foldIdx = -1
  for (let i = anchorIdx - 1; i >= segStart; i--) {
    if (messages[i].role === 'folded') { foldIdx = i; break }
  }

  // 收集区间：FOLDED 块之后（或段头 USER 之后）到 anchor 之前，全部并入
  const startIndex = foldIdx !== -1 ? foldIdx + 1 : segStart + (messages[segStart].role === 'user' ? 1 : 0)
  if (startIndex >= anchorIdx) return
  const pending = messages.slice(startIndex, anchorIdx)
  if (pending.length === 0) return

  if (foldIdx !== -1) {
    // 已有 FOLDED 块：合并元信息并物理删除 pending 明细
    const folded = messages[foldIdx]
    absorb(folded, pending)
    messages.splice(startIndex, pending.length)
  } else {
    // 新建 FOLDED 块：替换 pending 区间为单个折叠块（id 取区间最小 id，保持稳定 key）
    const info = summarize(pending)
    const newFolded: Message = {
      id: info.minId,
      role: 'folded',
      content: '',
      status: 'SUCCESS',
      foldedMinId: info.minId,
      foldedMaxId: info.maxId,
      assistantCount: info.assistantCount,
      toolCount: info.toolCount,
      errorDetails: info.errorDetails.length > 0 ? info.errorDetails : undefined
    }
    messages.splice(startIndex, pending.length, newFolded)
  }
}

/** 将 pending 明细的元信息合并进既有 FOLDED 块（区间扩张 + 计数累加 + 异常追加） */
const absorb = (folded: Message, pending: Message[]): void => {
  folded.foldedMinId = Math.min(folded.foldedMinId ?? folded.id, ...pending.map(m => m.id))
  folded.foldedMaxId = Math.max(folded.foldedMaxId ?? folded.id, ...pending.map(m => m.id))
  let assistantCount = folded.assistantCount || 0
  let toolCount = folded.toolCount || 0
  const errorDetails: FoldErrorDetail[] = [...(folded.errorDetails || [])]
  for (const m of pending) {
    if (isAssistantRole(m)) {
      assistantCount++
      if (m.status === 'FAILED') errorDetails.push({ kind: 'assistant' })
    } else if (m.role === 'tool') {
      toolCount++
      if (m.status === 'FAILED') errorDetails.push({ kind: 'tool', toolName: m.toolName || undefined })
    }
  }
  folded.assistantCount = assistantCount
  folded.toolCount = toolCount
  folded.errorDetails = errorDetails.length > 0 ? errorDetails : undefined
}

/** 汇总消息明细的折叠元信息（与后端 buildFoldedVo 扫描口径一致） */
const summarize = (msgs: Message[]) => {
  let minId = Number.MAX_SAFE_INTEGER
  let maxId = 0
  let assistantCount = 0
  let toolCount = 0
  const errorDetails: FoldErrorDetail[] = []
  for (const m of msgs) {
    if (typeof m.id === 'number') {
      minId = Math.min(minId, m.id)
      maxId = Math.max(maxId, m.id)
    }
    if (isAssistantRole(m)) {
      assistantCount++
      if (m.status === 'FAILED') errorDetails.push({ kind: 'assistant' })
    } else if (m.role === 'tool') {
      toolCount++
      if (m.status === 'FAILED') errorDetails.push({ kind: 'tool', toolName: m.toolName || undefined })
    }
  }
  return { minId, maxId, assistantCount, toolCount, errorDetails }
}

/**
 * 【D1 动态入口】新增消息（CREATED）：仅追加外露明细，不动折叠块。
 * 已存在同 id 消息时忽略（CREATED 不覆盖已有状态）；id 撞上 FOLDED 块时忽略（D4）。
 */
export const appendIncomingMessage = (messages: Message[], incoming: Message): void => {
  const existIdx = messages.findIndex(m => m.id === incoming.id)
  if (existIdx !== -1) {
    // 已存在同 id：CREATED 是占位事件，不覆盖本地已累积的状态/内容
    return
  }
  messages.push(incoming)
}

/**
 * 【D3 动态入口】助手类消息转终态（SUCCESS/FAILED/CANCELED）：
 * 上一个外露终态小组并入折叠块，自身成为新外露终态。幂等：区间为空时无操作，
 * 由 UPDATED 终态事件触发。
 */
export const onAssistantTerminal = (messages: Message[], msgId: number): void => {
  const idx = messages.findIndex(m => m.id === msgId)
  if (idx === -1) return
  const anchor = messages[idx]
  if (anchor.role === 'folded') return // D4 防御：id 撞上折叠块
  if (!isAssistantRole(anchor)) return
  const s = anchor.status
  if (s !== 'SUCCESS' && s !== 'FAILED' && s !== 'CANCELED') return
  foldUpTo(messages, idx)
}

/**
 * 【D2 动态入口】工具消息转 WAITING_APPROVAL：
 * 它所在小组整体起外露，之前的小组（含原外露终态助手）并入折叠块。
 */
export const onToolWaitingApproval = (messages: Message[], toolId: number): void => {
  const idx = messages.findIndex(m => m.id === toolId)
  if (idx === -1) return
  const tool = messages[idx]
  if (tool.role !== 'tool') return

  const [segStart] = locateSegment(messages, idx)
  const headIdx = findGroupHeadIdx(messages, segStart, idx)
  // anchor 为工具所在小组的助手头；孤儿工具则 anchor 为工具自身
  foldUpTo(messages, headIdx !== -1 ? headIdx : idx)
}

/**
 * 【D6 动态入口】reset 物理删除：保留 id <= targetId 的普通消息与 maxId <= targetId 的 FOLDED 块。
 * USER 消息不在折叠块内部，不存在跨界问题。
 */
export const applyResetDeletion = (messages: Message[], targetId: number): void => {
  const keep = messages.filter(m => {
    if (m.role === 'folded') return (m.foldedMaxId ?? m.id) <= targetId
    return m.id <= targetId
  })
  messages.splice(0, messages.length, ...keep)
}
