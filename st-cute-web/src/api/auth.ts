import request from '@/utils/request'

export interface UserInfo {
  username: string
  role: string
}

export const loginApi = async (password: string): Promise<UserInfo> => {
  return request.post('/api/auth/login', { password })
}

export const getUserInfoApi = async (): Promise<UserInfo> => {
  return request.get('/api/auth/info')
}
