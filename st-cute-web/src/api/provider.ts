import request from '@/utils/request'
import { Provider } from '@/types'

export const getProviders = async (): Promise<Provider[]> => {
  return request.get('/api/provider/list')
}

export const saveProvider = async (provider: Provider, originalModelName?: string): Promise<any> => {
  const url = originalModelName 
    ? `/api/provider/save?originalModelName=${encodeURIComponent(originalModelName)}`
    : '/api/provider/save'
  return request.post(url, provider)
}

export const activateProvider = async (id: string): Promise<any> => {
  return request.post(`/api/provider/active?id=${id}`)
}

export const deleteProvider = async (group: string, modelName: string): Promise<any> => {
  return request.delete(`/api/provider/delete?group=${encodeURIComponent(group)}&modelName=${encodeURIComponent(modelName)}`)
}
