import request from '@/utils/request'
import { Provider } from '@/types'

export const getProviders = async (): Promise<Provider[]> => {
  return request.get('/api/provider/list')
}

export const saveProvider = async (provider: Provider, originalGroup?: string, originalModelName?: string): Promise<any> => {
  // 编辑模式下携带原始分组与原始模型名，供后端联合定位待更新条目（支持编辑时修改分组名称）
  const params = new URLSearchParams()
  if (originalGroup) {
    params.append('originalGroup', originalGroup)
  }
  if (originalModelName) {
    params.append('originalModelName', originalModelName)
  }
  const query = params.toString()
  return request.post(query ? `/api/provider/save?${query}` : '/api/provider/save', provider)
}

export const activateProvider = async (id: string): Promise<any> => {
  return request.post(`/api/provider/active?id=${id}`)
}

export const deleteProvider = async (group: string, modelName: string): Promise<any> => {
  return request.delete(`/api/provider/delete?group=${encodeURIComponent(group)}&modelName=${encodeURIComponent(modelName)}`)
}
