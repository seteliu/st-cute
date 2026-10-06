/**
 * 【message 单元测试 — role 规范化唯一收口】
 *
 * 覆盖对象：src/utils/message.ts —— 后端大写枚举 role 到前端小写字面量的唯一转换点
 * 验证策略：
 *   1. 全部七种角色大写 → 小写映射正确
 *   2. 已是小写原样返回（引用不变，零拷贝）
 *   3. normalizeMessageList 批量转换与空值防御
 */
import { describe, it, expect } from 'vitest'
import { normalizeMessage, normalizeMessageList } from './message'
import type { Message } from '@/types'

describe('normalizeMessage — role 小写化收口', () => {
  it('七种角色大写枚举全部正确映射', () => {
    const roles = ['USER', 'ASSISTANT', 'SYSTEM', 'TOOL', 'BRANCH', 'COMPRESSED', 'FOLDED'] as const
    for (const r of roles) {
      const msg = { id: 1, role: r as any, content: '' } as Message
      expect(normalizeMessage(msg).role).toBe(r.toLowerCase())
    }
  })

  it('已是小写时原样返回且引用不变（零拷贝）', () => {
    const msg: Message = { id: 1, role: 'assistant', content: 'x' }
    expect(normalizeMessage(msg)).toBe(msg)
  })

  it('大写时返回新对象，原对象不被修改', () => {
    // 后端大写枚举入参，经 unknown 中转模拟线上脏数据形态
    const msg = { id: 1, role: 'ASSISTANT', content: 'x' } as unknown as Message
    const out = normalizeMessage(msg)
    expect(out).not.toBe(msg)
    expect(out.role).toBe('assistant')
    expect(msg.role).toBe('ASSISTANT')
  })

  it('null/undefined 入参防御', () => {
    expect(normalizeMessage(null as any)).toBe(null)
    expect(normalizeMessage(undefined as any)).toBe(undefined)
  })
})

describe('normalizeMessageList — 批量规范化', () => {
  it('混合列表全量转换，返回新数组', () => {
    const list = [
      { id: 1, role: 'USER', content: 'a' },
      { id: 2, role: 'assistant', content: 'b' },
      { id: 3, role: 'TOOL', content: '' }
    ] as Message[]
    const out = normalizeMessageList(list)
    expect(out.map((m) => m.role)).toEqual(['user', 'assistant', 'tool'])
    expect(out[1]).toBe(list[1]) // 已小写项引用不变
  })

  it('null/undefined 防御返回空数组', () => {
    expect(normalizeMessageList(null)).toEqual([])
    expect(normalizeMessageList(undefined)).toEqual([])
  })
})
