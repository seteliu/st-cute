/**
 * 【trimMessages 单元测试 — 视图保留上限裁剪】
 *
 * 覆盖对象：src/utils/trimMessages.ts —— 消息列表按 USER 边界整段裁剪
 * 验证策略：
 *   1. 未超限原样返回（truncated: false）
 *   2. 超限时从目标下标回溯最近 USER，整段保留不切断对话轮
 *   3. 找不到 USER 边界时放弃裁剪（全量返回）
 */
import { describe, it, expect } from 'vitest'
import { trimMessagesArray } from './trimMessages'
import type { Message } from '@/types'

const m = (id: number, role: Message['role']): Message => ({ id, role, content: '' })

describe('trimMessagesArray — 裁剪策略', () => {
  it('未超限时原样返回且不截断', () => {
    const list = [m(1, 'user'), m(2, 'assistant')]
    const { list: out, truncated } = trimMessagesArray(list, 10)
    expect(out).toBe(list)
    expect(truncated).toBe(false)
  })

  it('恰好等于 limit 时不算超限', () => {
    const list = [m(1, 'user'), m(2, 'assistant')]
    const { truncated } = trimMessagesArray(list, 2)
    expect(truncated).toBe(false)
  })

  it('超限时回溯到 USER 边界整段保留', () => {
    // 10 条消息 limit 5：targetIndex 从 5 回溯到下标 2 的 USER
    const list = [
      m(1, 'user'),
      m(2, 'assistant'),
      m(3, 'user'),
      m(4, 'assistant'),
      m(5, 'tool'),
      m(6, 'assistant'),
      m(7, 'user'),
      m(8, 'assistant'),
      m(9, 'tool'),
      m(10, 'assistant')
    ]
    const { list: out, truncated } = trimMessagesArray(list, 5)
    expect(truncated).toBe(true)
    // targetIndex=5（id=6）回溯到 USER(id=3)，slice(2) 保留 3~10 共 8 条（宁多勿切）
    expect(out[0].id).toBe(3)
    expect(out.map((x) => x.id)).toEqual([3, 4, 5, 6, 7, 8, 9, 10])
  })

  it('大写 USER 同样识别（后端未归一化防御）', () => {
    const list = [
      m(1, 'user'),
      m(2, 'assistant'),
      m(3, 'USER' as Message['role']),
      m(4, 'assistant'),
      m(5, 'assistant'),
      m(6, 'assistant')
    ]
    const { list: out, truncated } = trimMessagesArray(list, 3)
    expect(truncated).toBe(true)
    expect(out[0].id).toBe(3)
  })

  it('回溯不到 USER 边界时放弃裁剪全量返回', () => {
    const list = [m(1, 'assistant'), m(2, 'assistant'), m(3, 'assistant')]
    const { list: out, truncated } = trimMessagesArray(list, 1)
    expect(out).toBe(list)
    expect(truncated).toBe(false)
  })

  it('超长列表在窗口边界不切断对话轮', () => {
    // 构造 12 条、limit 5：targetIndex=7 回溯到 USER(6) 整段保留
    const list = Array.from({ length: 12 }, (_, i) => m(i + 1, i % 4 === 0 ? 'user' : 'assistant'))
    const { list: out, truncated } = trimMessagesArray(list, 5)
    expect(truncated).toBe(true)
    expect(out[0].role).toBe('user')
    // 保留段内无被切断的对话轮（首条必为 user）
    expect(out.length).toBeLessThanOrEqual(12)
  })
})
