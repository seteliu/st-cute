import request from '@/utils/request'

export interface BasicConfig {
  language?: 'zh-CN' | 'en-US'
  newlineKey: 'enter' | 'alt+enter'
  httpLog: boolean
  httpLogDays: number
  password?: string
  messageAggregation?: boolean
  maxViewHistoryLimit?: number
  pathSandboxEnabled?: boolean
}

export const getConfigApi = async (): Promise<BasicConfig> => {
  return request.get('/api/config/list')
}

export const saveConfigApi = async (config: BasicConfig): Promise<any> => {
  return request.post('/api/config/save', config)
}
