import request from '@/utils/request'
import { sha256Hex } from '@/utils/digest'

export interface UserInfo {
  username: string
  role: string
}

export const loginApi = async (password: string): Promise<UserInfo> => {
  // 传输加密：原文密码先在本地转 SHA-256 摘要再发送，明文不经过网络传输层
  const digest = await sha256Hex(password)
  return request.post('/api/auth/login', { password: digest })
}

export const getUserInfoApi = async (): Promise<UserInfo> => {
  return request.get('/api/auth/info')
}
