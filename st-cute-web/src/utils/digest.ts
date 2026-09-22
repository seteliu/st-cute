/**
 * 安全摘要工具：密码类敏感值在离开浏览器前统一转为 SHA-256 摘要传输。
 * <p>
 * 与后端约定：登录与保存密码发送的均为 SHA-256(原文) 十六进制小写摘要，
 * 原文密码不经过网络传输层；服务端存储形态为带盐摘要（前端无需感知）。
 * </p>
 * <p>
 * 实现说明：此处刻意不使用 Web Crypto（crypto.subtle）。该 API 仅在安全上下文
 * （https、localhost、127.0.0.1）下才被暴露，通过 http + 域名/IP 访问时
 * crypto.subtle 为 undefined，会导致摘要计算失败并静默降级为空串，
 * 表现为"密码明明已输入，请求体里 password 却是空串"。
 * 改用纯实现后，任意协议、任意域名下的计算结果完全一致。
 * </p>
 */

/** SHA-256 轮常量：前 64 个质数立方根小数部分的前 32 位 */
const ROUND_CONSTANTS = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
])

/** 初始哈希值：前 8 个质数平方根小数部分的前 32 位 */
const INITIAL_HASH = new Uint32Array([
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
])

/**
 * 32 位无符号数循环右移
 * @param value 待移位数值
 * @param amount 位移位数
 * @returns 循环右移结果
 */
function rightRotate(value: number, amount: number): number {
  return (value >>> amount) | (value << (32 - amount))
}

/**
 * 计算字节数组的 SHA-256 摘要（FIPS 180-4 标准实现）
 * @param bytes 输入字节
 * @returns 32 字节摘要
 */
function sha256Bytes(bytes: Uint8Array): Uint8Array {
  // 消息填充：追加 0x80、若干 0x00，末尾 8 字节为大端位长度，使总长为 64 字节整数倍
  const bitLength = bytes.length * 8
  const paddedLength = (((bytes.length + 8) >> 6) + 1) << 6
  const padded = new Uint8Array(paddedLength)
  padded.set(bytes)
  padded[bytes.length] = 0x80
  const view = new DataView(padded.buffer)
  view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000), false)
  view.setUint32(paddedLength - 4, bitLength >>> 0, false)

  const hash = new Uint32Array(INITIAL_HASH)
  const w = new Uint32Array(64)

  for (let offset = 0; offset < paddedLength; offset += 64) {
    // 前 16 个字直接取自分组，其余由消息调度函数扩展
    for (let i = 0; i < 16; i++) {
      w[i] = view.getUint32(offset + i * 4, false)
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rightRotate(w[i - 15], 7) ^ rightRotate(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rightRotate(w[i - 2], 17) ^ rightRotate(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0
    }

    let a = hash[0]
    let b = hash[1]
    let c = hash[2]
    let d = hash[3]
    let e = hash[4]
    let f = hash[5]
    let g = hash[6]
    let h = hash[7]

    for (let i = 0; i < 64; i++) {
      const sigma1 = rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25)
      const choose = (e & f) ^ (~e & g)
      const temp1 = (h + sigma1 + choose + ROUND_CONSTANTS[i] + w[i]) >>> 0
      const sigma0 = rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)
      const majority = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (sigma0 + majority) >>> 0

      h = g
      g = f
      f = e
      e = (d + temp1) >>> 0
      d = c
      c = b
      b = a
      a = (temp1 + temp2) >>> 0
    }

    hash[0] = (hash[0] + a) >>> 0
    hash[1] = (hash[1] + b) >>> 0
    hash[2] = (hash[2] + c) >>> 0
    hash[3] = (hash[3] + d) >>> 0
    hash[4] = (hash[4] + e) >>> 0
    hash[5] = (hash[5] + f) >>> 0
    hash[6] = (hash[6] + g) >>> 0
    hash[7] = (hash[7] + h) >>> 0
  }

  const result = new Uint8Array(32)
  const resultView = new DataView(result.buffer)
  for (let i = 0; i < 8; i++) {
    resultView.setUint32(i * 4, hash[i], false)
  }
  return result
}

/**
 * 计算文本的 SHA-256 十六进制摘要（小写），与后端 PasswordDigestKit.sha256Hex 同算法
 * @param text 原文
 * @returns 64 位十六进制摘要字符串
 */
export async function sha256Hex(text: string): Promise<string> {
  // TextEncoder 不受安全上下文限制，任意协议与域名下均可用
  const digest = sha256Bytes(new TextEncoder().encode(text))
  let hex = ''
  for (const byte of digest) {
    hex += byte.toString(16).padStart(2, '0')
  }
  return hex
}

/**
 * 计算字节数组的 SHA-256 摘要并返回 Base64 编码，与后端 PasswordDigestKit.hash 的摘要段同算法。
 * <p>用于登录质询-应答协议中重算存储摘要 D（服务端存储形态为 Base64(salt:digest)）</p>
 * @param bytes 输入字节（盐字节 + 原文摘要的 UTF-8 字节）
 * @returns Base64 编码的摘要字符串
 */
export function sha256Base64(bytes: Uint8Array): string {
  const digest = sha256Bytes(bytes)
  let bin = ''
  for (const byte of digest) {
    bin += String.fromCharCode(byte)
  }
  return btoa(bin)
}
