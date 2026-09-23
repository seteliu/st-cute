import request from '@/utils/request'
import { sha256Hex } from '@/utils/digest'
import { BasicConfig, PasswordPayload } from '@/types'

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
export const saveConfigApi = async (config: BasicConfig): Promise<void> => {
  return request.post('/api/config/save', config)
}

/**
 * 设置 / 修改安全密码：明文在本地先转 SHA-256 摘要再发送，原文不经过网络。
 *
 * @param rawPassword 用户输入的密码原文
 */
export const savePasswordApi = async (rawPassword: string): Promise<void> => {
  const payload: PasswordPayload = {
    password: await sha256Hex(rawPassword),
    passwordLength: rawPassword.length
  }
  return request.post('/api/config/password', payload)
}

/**
 * 清除安全密码：系统回到未启用密码保护的状态。
 */
export const clearPasswordApi = async (): Promise<void> => {
  return request.post('/api/config/password', { passwordClear: true } as PasswordPayload)
}
