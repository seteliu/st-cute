import request from '@/utils/request'
import { sha256Hex } from '@/utils/digest'

export interface BasicConfig {
  language?: 'zh-CN' | 'en-US'
  newlineKey: 'enter' | 'alt+enter'
  httpLog: boolean
  httpLogDays: number
  /** 是否已设置安全密码（查询接口回传的状态标记；密码本身全链路不回传） */
  passwordSet?: boolean
  maxViewHistoryLimit?: number
  pathSandboxEnabled?: boolean
  minimalSkillMode?: boolean
}

/** 安全密码操作请求体（设置/修改/清除） */
export interface PasswordPayload {
  /** 原文密码的 SHA-256 传输摘要（清除时可不传） */
  password?: string
  /** password 摘要对应的原文长度：随摘要附带，供后端兜底校验复杂度策略 */
  passwordLength?: number
  /** 显式清除标记：true 时清除密码（优先级高于 password） */
  passwordClear?: boolean
}

export const getConfigApi = async (): Promise<BasicConfig> => {
  return request.get('/api/config/list')
}

/**
 * 保存基础配置（不含密码：密码走 {@link savePasswordApi} 专用接口）。
 * <p>
 * 与密码解耦是刻意的：密码若随常驻表单同进同出，浏览器自动填充会把存储摘要
 * 回填进输入框并被二次摘要，导致密码静默失效。
 * </p>
 */
export const saveConfigApi = async (config: BasicConfig): Promise<any> => {
  return request.post('/api/config/save', config)
}

/**
 * 设置 / 修改安全密码：明文在本地先转 SHA-256 摘要再发送，原文不经过网络。
 *
 * @param rawPassword 用户输入的密码原文
 */
export const savePasswordApi = async (rawPassword: string): Promise<any> => {
  const payload: PasswordPayload = {
    password: await sha256Hex(rawPassword),
    passwordLength: rawPassword.length
  }
  return request.post('/api/config/password', payload)
}

/**
 * 清除安全密码：系统回到未启用密码保护的状态。
 */
export const clearPasswordApi = async (): Promise<any> => {
  return request.post('/api/config/password', { passwordClear: true } as PasswordPayload)
}
