/**
 * 【foldEngine 单元测试】
 *
 * 覆盖对象：src/utils/foldEngine.ts —— 折叠视图共享引擎（主会话与子会话复用）
 * 覆盖范围：
 *   1. buildRenderItems 静态组装：SYSTEM 跳过 / 工具嵌套 / 孤儿组 / 空助手过滤 / FOLDED 保序
 *   2. isInFoldedRange：D4 迟到事件忽略判定
 *   3. 动态增量规则 D1~D6：追加 / 待审批折叠 / 终态折叠 / reset 物理删除
 *   4. 折叠块元信息：区间 / 计数 / 异常明细
 */
import { describe, it, expect } from 'vitest'
import {
  buildRenderItems,
  isInFoldedRange,
  appendIncomingMessage,
  onAssistantTerminal,
  onToolWaitingApproval,
  applyResetDeletion
} from './foldEngine'
import type { Message } from '@/types'

// ---------- 测试数据工厂：最小合法 Message，按需覆盖字段 ----------

let nextId = 1
const genId = (): number => nextId++

const user = (over: Partial<Message> = {}): Message => ({
  id: genId(),
  role: 'user',
  content: '用户消息',
  ...over
})

const assistant = (over: Partial<Message> = {}): Message => ({
  id: genId(),
  role: 'assistant',
  content: '助手回复',
  status: 'SUCCESS',
  ...over
})

/** 空助手：五无判定对象（无 content 无 thought 且 SUCCESS 且名下无 TOOL） */
const blankAssistant = (): Message => assistant({ content: '' })

const tool = (over: Partial<Message> = {}): Message => ({
  id: genId(),
  role: 'tool',
  content: '',
  status: 'SUCCESS',
  toolName: 'read_file',
  ...over
})

const foldedBlock = (over: Partial<Message> = {}): Message => ({
  id: genId(),
  role: 'folded',
  content: '',
  status: 'SUCCESS',
  foldedMinId: 1,
  foldedMaxId: 1,
  assistantCount: 1,
  toolCount: 0,
  ...over
})

/** 提取渲染序列的可读签名，便于断言整体形态 */
const shape = (items: ReturnType<typeof buildRenderItems>): string[] =>
  items.map((i) => (i.type === 'message' && i.tools ? 'message+tools' : i.type))

describe('buildRenderItems — 静态组装', () => {
  it('普通消息按原序输出，SYSTEM 被跳过', () => {
    const u = user()
    const a1 = assistant()
    const sys: Message = { id: genId(), role: 'system', content: 'sys' }
    const a2 = assistant()
    const items = buildRenderItems([u, a1, sys, a2])
    expect(items).toHaveLength(3)
    expect(items[0].type === 'message' && items[0].data.id).toBe(u.id)
    expect(items[1].type === 'message' && items[1].data.id).toBe(a1.id)
    expect(items[2].type === 'message' && items[2].data.id).toBe(a2.id)
  })

  it('TOOL 按 parentMessageId 嵌套进父助手形成原子小组', () => {
    const u = user()
    const a = assistant()
    const t1 = tool({ parentMessageId: a.id })
    const t2 = tool({ parentMessageId: a.id })
    const items = buildRenderItems([u, a, t1, t2])
    expect(items).toHaveLength(2)
    const entry = items[1]
    expect(entry.type).toBe('message')
    expect(entry.type === 'message' && entry.tools!.map((t) => t.id)).toEqual([t1.id, t2.id])
  })

  it('纯工具调用助手（名下有 TOOL）不被过滤', () => {
    const u = user()
    const a = assistant({ content: '' })
    const t = tool({ parentMessageId: a.id })
    const items = buildRenderItems([u, a, t])
    expect(shape(items)).toEqual(['message', 'message+tools'])
  })

  it('五无空助手被过滤', () => {
    const u = user()
    const blank = blankAssistant()
    const a2 = assistant()
    const items = buildRenderItems([u, blank, a2])
    expect(items).toHaveLength(2)
    expect(items[1].type === 'message' && items[1].data.id).toBe(a2.id)
  })

  it('有 thought 或 FAILED 的空内容助手不被过滤', () => {
    const u = user()
    const withThought = assistant({ content: '', thought: '思考中' })
    const failed = assistant({ content: '', status: 'FAILED' })
    const items = buildRenderItems([u, withThought, failed])
    expect(items).toHaveLength(3)
  })

  it('FOLDED 块原样保留且保持原位，不堆积到顶部', () => {
    const u = user()
    const f = foldedBlock()
    const a = assistant()
    const items = buildRenderItems([u, f, a])
    expect(items.map((i) => i.type)).toEqual(['message', 'folded', 'message'])
  })

  it('无父消息的孤儿工具独立成组，追加在序列末尾', () => {
    const u = user()
    const a = assistant()
    const o1 = tool()
    const o2 = tool()
    const items = buildRenderItems([u, a, o1, o2])
    expect(items.map((i) => i.type)).toEqual(['message', 'message', 'tool_group'])
    const tg = items[2]
    expect(tg.type === 'tool_group' && tg.tools.map((t) => t.id)).toEqual([o1.id, o2.id])
  })

  it('空列表返回空序列', () => {
    expect(buildRenderItems([])).toEqual([])
  })
})

describe('isInFoldedRange — D4 迟到事件忽略判定', () => {
  it('id 落在闭区间内返回 true，含边界', () => {
    const f = foldedBlock({ foldedMinId: 10, foldedMaxId: 20 })
    const messages = [user(), f, assistant()]
    expect(isInFoldedRange(messages, 10)).toBe(true)
    expect(isInFoldedRange(messages, 15)).toBe(true)
    expect(isInFoldedRange(messages, 20)).toBe(true)
  })

  it('id 在区间之外返回 false', () => {
    const f = foldedBlock({ foldedMinId: 10, foldedMaxId: 20 })
    const messages = [user(), f, assistant()]
    expect(isInFoldedRange(messages, 9)).toBe(false)
    expect(isInFoldedRange(messages, 21)).toBe(false)
  })

  it('无折叠块或折叠块缺区间信息时不误判', () => {
    expect(isInFoldedRange([user(), assistant()], 5)).toBe(false)
    const f = foldedBlock({ foldedMinId: undefined, foldedMaxId: undefined })
    expect(isInFoldedRange([user(), f], 5)).toBe(false)
  })
})

describe('appendIncomingMessage — D1 动态追加', () => {
  it('新消息直接追加外露，不动既有内容', () => {
    const u = user()
    const a = assistant()
    const messages = [u, a]
    const incoming = assistant({ id: 999, content: '新消息' })
    appendIncomingMessage(messages, incoming)
    expect(messages).toHaveLength(3)
    expect(messages[2].id).toBe(999)
  })

  it('已存在同 id 消息时忽略，不覆盖本地已累积的状态', () => {
    const u = user()
    const a = assistant({ id: 50, status: 'RUNNING', content: '流式中' })
    const messages = [u, a]
    const incoming = assistant({ id: 50, status: 'SUCCESS', content: '覆盖尝试' })
    appendIncomingMessage(messages, incoming)
    expect(messages).toHaveLength(2)
    expect(messages[1].status).toBe('RUNNING')
    expect(messages[1].content).toBe('流式中')
  })
})

describe('onAssistantTerminal — D3 终态折叠', () => {
  it('助手转终态时，之前的已完成小组并入新建折叠块，自身外露', () => {
    const u = user()
    const a1 = assistant()
    const a2 = assistant()
    const a3 = assistant({ status: 'RUNNING' })
    const messages = [u, a1, a2, a3]
    // 真实链路：store 先收到 UPDATED 事件将状态置为终态，再调引擎入口
    a3.status = 'SUCCESS'
    onAssistantTerminal(messages, a3.id)
    // 形态：USER + FOLDED(含 a1,a2) + 外露终态 a3
    expect(messages).toHaveLength(3)
    expect(messages[1].role).toBe('folded')
    expect(messages[1].foldedMinId).toBe(a1.id)
    expect(messages[1].foldedMaxId).toBe(a2.id)
    expect(messages[1].assistantCount).toBe(2)
    expect(messages[2].id).toBe(a3.id)
    expect(messages[2].status).toBe('SUCCESS')
  })

  it('再次转终态时扩既有折叠块区间并累加计数，明细被物理删除', () => {
    const u = user()
    const a1 = assistant()
    const a2 = assistant()
    const a3 = assistant({ status: 'RUNNING' })
    const a4 = assistant({ status: 'RUNNING' })
    const messages = [u, a1, a2, a3, a4]
    a3.status = 'SUCCESS'
    onAssistantTerminal(messages, a3.id) // a1,a2 折叠
    a4.status = 'SUCCESS'
    onAssistantTerminal(messages, a4.id) // a3 并入折叠块
    expect(messages).toHaveLength(3)
    const f = messages[1]
    expect(f.role).toBe('folded')
    expect(f.foldedMinId).toBe(a1.id)
    expect(f.foldedMaxId).toBe(a3.id)
    expect(f.assistantCount).toBe(3)
  })

  it('FAILED 明细进入 errorDetails 异常清单', () => {
    const u = user()
    const ok = assistant()
    const bad = assistant({ status: 'FAILED' })
    const tail = assistant({ status: 'RUNNING' })
    const messages = [u, ok, bad, tail]
    tail.status = 'SUCCESS'
    onAssistantTerminal(messages, tail.id)
    const f = messages[1]
    expect(f.role).toBe('folded')
    expect(f.errorDetails).toEqual([{ kind: 'assistant' }])
  })

  it('段内无前置小组时无操作（首个助手转终态不折叠 USER）', () => {
    const u = user()
    const a1 = assistant({ status: 'RUNNING' })
    const messages = [u, a1]
    onAssistantTerminal(messages, a1.id)
    expect(messages.map((m) => m.role)).toEqual(['user', 'assistant'])
  })

  it('工具小组整体随前序并入折叠块，计数含工具', () => {
    const u = user()
    const a1 = assistant()
    const t1 = tool({ parentMessageId: a1.id })
    const a2 = assistant()
    const tail = assistant({ status: 'RUNNING' })
    const messages = [u, a1, t1, a2, tail]
    // 真实链路：UPDATED 事件先置终态，再触发引擎入口
    tail.status = 'SUCCESS'
    onAssistantTerminal(messages, tail.id)
    expect(messages).toHaveLength(3)
    const f = messages[1]
    expect(f.role).toBe('folded')
    expect(f.assistantCount).toBe(2)
    expect(f.toolCount).toBe(1)
    expect(f.foldedMinId).toBe(a1.id)
    expect(f.foldedMaxId).toBe(a2.id)
  })

  it('id 撞上折叠块或消息不存在时安全忽略', () => {
    const f = foldedBlock({ foldedMinId: 1, foldedMaxId: 9 })
    const messages = [user(), f, assistant()]
    onAssistantTerminal(messages, f.id)
    expect(messages).toHaveLength(3)
    onAssistantTerminal(messages, 424242)
    expect(messages).toHaveLength(3)
  })

  it('非终态（RUNNING）触发时无操作', () => {
    const u = user()
    const a1 = assistant()
    const a2 = assistant({ status: 'RUNNING' })
    const messages = [u, a1, a2]
    onAssistantTerminal(messages, a2.id)
    expect(messages.map((m) => m.role)).toEqual(['user', 'assistant', 'assistant'])
  })
})

describe('onToolWaitingApproval — D2 待审批折叠', () => {
  it('工具转待审批时，其父助手小组起外露，之前小组并入折叠块', () => {
    const u = user()
    const a1 = assistant()
    const a2 = assistant()
    const a3 = assistant({ content: '' })
    const t3 = tool({ parentMessageId: a3.id, status: 'WAITING_APPROVAL' })
    const messages = [u, a1, a2, a3, t3]
    onToolWaitingApproval(messages, t3.id)
    // 形态：USER + FOLDED(a1,a2) + 外露小组(a3+t3)
    expect(messages).toHaveLength(4)
    expect(messages[1].role).toBe('folded')
    expect(messages[1].foldedMaxId).toBe(a2.id)
    expect(messages[2].id).toBe(a3.id)
    expect(messages[3].id).toBe(t3.id)
    // 渲染层视角：a3 与 t3 重组为原子小组
    const items = buildRenderItems(messages)
    expect(shape(items)).toEqual(['message', 'folded', 'message+tools'])
  })

  it('段首小组即待审批时无操作（无前序可折叠）', () => {
    const u = user()
    const a1 = assistant({ content: '' })
    const t1 = tool({ parentMessageId: a1.id, status: 'WAITING_APPROVAL' })
    const messages = [u, a1, t1]
    onToolWaitingApproval(messages, t1.id)
    expect(messages).toHaveLength(3)
    expect(messages.some((m) => m.role === 'folded')).toBe(false)
  })

  it('非工具消息触发时安全忽略', () => {
    const u = user()
    const a1 = assistant()
    const a2 = assistant()
    const messages = [u, a1, a2]
    onToolWaitingApproval(messages, a2.id)
    expect(messages).toHaveLength(3)
  })
})

describe('applyResetDeletion — D6 reset 物理删除', () => {
  it('保留 id 不大于 targetId 的普通消息', () => {
    const u = user()
    const a1 = assistant()
    const a2 = assistant()
    const messages = [u, a1, a2]
    applyResetDeletion(messages, a1.id)
    expect(messages.map((m) => m.id)).toEqual([u.id, a1.id])
  })

  it('FOLDED 块按 foldedMaxId 判定去留', () => {
    const u = user()
    const f = foldedBlock({ foldedMinId: 5, foldedMaxId: 10 })
    const a = assistant()
    const messages = [u, f, a]
    // targetId 8 < maxId 10：折叠块整体删除
    applyResetDeletion(messages, 8)
    expect(messages.some((m) => m.role === 'folded')).toBe(false)
  })

  it('targetId 覆盖折叠块区间时折叠块保留', () => {
    // 显式指定 id，避免动态 genId 超出 targetId 导致误删
    const u = user({ id: 1 })
    const f = foldedBlock({ id: 6, foldedMinId: 5, foldedMaxId: 10 })
    const a = assistant({ id: 11 })
    const messages = [u, f, a]
    applyResetDeletion(messages, 12)
    expect(messages.map((m) => m.role)).toEqual(['user', 'folded', 'assistant'])
  })

  it('全部越界时清空列表', () => {
    const u = user()
    const a = assistant()
    const messages = [u, a]
    applyResetDeletion(messages, 0)
    expect(messages).toHaveLength(0)
  })
})
