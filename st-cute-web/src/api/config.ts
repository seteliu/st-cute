import request from '@/utils/request'
import { sha256Hex } from '@/utils/digest'

export interface BasicConfig {
  language?: 'zh-CN' | 'en-US'
  newlineKey: 'enter' | 'alt+enter'
  httpLog: boolean
  httpLogDays: number
  /** 保存时：原文密码的 SHA-256 传输摘要（空串/缺省 = 不修改）；查询接口不回传该字段 */
  password?: string
  /** 是否已设置安全密码（查询接口回传的状态标记，替代明文回显） */
  passwordSet?: boolean
  /** 显式清除密码标记：true 时清除已设置的访问密码（优先级高于 password 字段） */
  passwordClear?: boolean
  maxViewHistoryLimit?: number
  pathSandboxEnabled?: boolean
  minimalSkillMode?: boolean
}

export const getConfigApi = async (): Promise<BasicConfig> => {
  return request.get('/api/config/list')
}

/**
 * 保存基础配置。密码字段约定：明文在本地先转 SHA-256 摘要再发送；
 * 输入空串/空白时以 undefined 发送（后端保持原密码不变，是否清除由 passwordClear 标记决定）
 */
export const saveConfigApi = async (config: BasicConfig): Promise<any> => {
  const payload: BasicConfig = { ...config }
  if (payload.password !== undefined && payload.password.trim() !== '') {
    payload.password = await sha256Hex(payload.password)
  } else {
    delete payload.password
  }
  return request.post('/api/config/save', payload)
}
