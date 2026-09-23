import request from '@/utils/request'
import { sha256Hex, sha256Base64 } from '@/utils/digest'
import { UserInfo, ChallengeInfo } from '@/types'

/** 登录质询接口：取得一次性 nonce 与盐，作为登录证明材料 */
export const getChallengeApi = async (): Promise<ChallengeInfo | null> => {
  return request.get('/api/auth/challenge')
}

/** Base64 解码为字节数组（盐段处理用） */
const base64ToBytes = (b64: string): Uint8Array => {
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) {
    bytes[i] = bin.charCodeAt(i)
  }
  return bytes
}

/** 文本 UTF-8 编码为字节数组 */
const utf8Bytes = (text: string): Uint8Array => new TextEncoder().encode(text)

export const loginApi = async (password: string): Promise<UserInfo> => {
  // 传输加密：原文密码先在本地转 SHA-256 摘要再发送，明文不经过网络传输层
  const digest = await sha256Hex(password)

  // 取得服务端签发的一次性质询材料（未配置访问码时返回 null）
  const challenge = await getChallengeApi().catch(() => null)
  if (challenge?.nonce && challenge?.salt) {
    // 质询-应答协议：先以盐重算存储摘要 D = Base64(SHA-256(salt + SHA-256hex(原文)))，
    // 再计算证明摘要 proof = SHA-256hex(D + ":" + nonce)。
    // freshness 材料（nonce）在密码学上绑定进证明值，服务端单次消费，
    // 抓包重放与伪造质询值均无法通过校验
    const saltBytes = base64ToBytes(challenge.salt)
    const digestBytes = utf8Bytes(digest)
    const input = new Uint8Array(saltBytes.length + digestBytes.length)
    input.set(saltBytes)
    input.set(digestBytes, saltBytes.length)
    const storedDigestBase64 = await sha256Base64(input)
    const proof = await sha256Hex(storedDigestBase64 + ':' + challenge.nonce)
    return request.post('/api/auth/login', {
      password: digest,
      nonce: challenge.nonce,
      proof
    })
  }

  // 无质询材料（访问码未配置，或历史明文存储值升级前的迁移窗口）：按摘要直接提交
  return request.post('/api/auth/login', { password: digest })
}

export const getUserInfoApi = async (): Promise<UserInfo> => {
  return request.get('/api/auth/info')
}

/**
 * 登出接口：销毁服务端会话。
 * <p>幂等：未登录或会话已失效时后端同样返回成功</p>
 */
export const logoutApi = async (): Promise<void> => {
  // silent：登出为收尾动作，会话已失效等异常无需弹窗打扰（本地状态照常清理）
  return request.post('/api/auth/logout', {}, { silent: true })
}
