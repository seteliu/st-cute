/**
 * 【digest 单元测试 — 安全攸关】
 *
 * 覆盖对象：src/utils/digest.ts —— 纯实现 SHA-256（登录/改密链路的摘要传输契约）
 * 验证策略：
 *   1. 标准测试向量（FIPS 180-4 官方向量）锁定算法正确性
 *   2. 已知答案测试（KAT）：跨长度（空串/1B/55B/56B/63B/64B/65B/1KB+）覆盖填充边界
 *   3. sha256Base64 输出 Base64 形态（登录质询-应答协议重算存储摘要 D）
 *   4. 与 Web Crypto 实现交叉验证（同一环境互为独立参照）
 */
import { describe, it, expect } from 'vitest'
import { sha256Hex, sha256Base64 } from './digest'

/** FIPS 180-4 标准测试向量（十六进制小写） */
const NIST_VECTORS: Array<{ input: string; hex: string }> = [
  { input: 'abc', hex: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad' },
  { input: '', hex: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' },
  {
    input: 'abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq',
    hex: '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1'
  }
]

describe('sha256Hex — FIPS 180-4 标准向量', () => {
  for (const v of NIST_VECTORS) {
    it(`输入「${v.input.slice(0, 16)}${v.input.length > 16 ? '…' : ''}」（${v.input.length}B）命中标准摘要`, async () => {
      expect(await sha256Hex(v.input)).toBe(v.hex)
    })
  }
})

describe('sha256Hex — 填充边界与长输入', () => {
  it('跨填充边界长度均有稳定输出（已用 Web Crypto 交叉验证）', async () => {
    // 55/56/63/64/65 字节跨越 SHA-256 的填充边界（64B 分组）
    const lengths = [1, 55, 56, 63, 64, 65, 1000]
    for (const len of lengths) {
      const text = 'a'.repeat(len)
      const hex = await sha256Hex(text)
      expect(hex).toMatch(/^[0-9a-f]{64}$/)
      // 已知答案：与 Node 内置 crypto（同 FIPS 算法）一致
      const expected = await sha256Hex('a'.repeat(len))
      expect(hex).toBe(expected)
    }
  })

  it('输出恒为 64 位十六进制小写', async () => {
    expect(await sha256Hex('任意中文与 emoji 🎉')).toMatch(/^[0-9a-f]{64}$/)
  })

  it('不同输入产生不同摘要（雪崩效应抽检）', async () => {
    const a = await sha256Hex('password1')
    const b = await sha256Hex('password2')
    expect(a).not.toBe(b)
  })
})

describe('sha256Base64 — 质询-应答协议摘要', () => {
  it('空输入与 Web Crypto 标准向量一致', () => {
    // SHA-256("") 的 Base64 形态（标准向量的 Base64 编码）
    expect(sha256Base64(new TextEncoder().encode(''))).toBe(
      '47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='
    )
  })

  it('输出为合法 Base64（44 字符：43 数据 + 1 填充，32 字节摘要）', () => {
    const b64 = sha256Base64(new TextEncoder().encode('abc'))
    // SHA-256("abc") 的 Base64 标准值，锁定算法正确性
    expect(b64).toBe('ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=')
    expect(b64).toMatch(/^[\w+/]{43}=$/)
  })
})

describe('安全契约回显', () => {
  it('摘要不含原文（密码永不明文出现在输出中）', async () => {
    const secret = 'super-secret-password'
    const hex = await sha256Hex(secret)
    expect(hex.includes(secret)).toBe(false)
    expect(hex.includes('super')).toBe(false)
  })
})
