import request from '@/utils/request'
import { Message, Conversation, LimitMessageDto, ApproveToolPayload, ActiveProcessInfo, ActiveLlmCallInfo } from '@/types'

// silent=true 标识后台静默请求（断线重连强刷场景）：失败时拦截器不弹全局错误提示，由调用方兜底
export const getConversations = async (silent = false): Promise<Conversation[]> => {
  return request.get('/api/conversation/list', { silent })
}

export const getConversationMessages = async (
  cid: number,
  options?: { folded?: boolean; minId?: number; maxId?: number },
  silent = false
): Promise<LimitMessageDto> => {
  const folded = options?.folded !== undefined ? options.folded : true
  let url = `/api/message/list?cid=${cid}&folded=${folded}`
  if (options?.minId !== undefined) url += `&minId=${options.minId}`
  if (options?.maxId !== undefined) url += `&maxId=${options.maxId}`
  return request.get(url, { silent })
}

export const deleteConversationById = async (id: number): Promise<void> => {
  return request.delete(`/api/conversation/delete?id=${id}`)
}

export const batchDeleteConversationsApi = async (ids: number[]): Promise<void> => {
  return request.post('/api/conversation/batch-delete', ids)
}

export const createConversationApi = async (conversation: Partial<Conversation>): Promise<Conversation> => {
  return request.post('/api/conversation/create', conversation)
}

export const updateConversationProviderApi = async (cid: number, providerGroup: string, providerModelName: string): Promise<void> => {
  return request.post(`/api/conversation/update-provider?id=${cid}&providerGroup=${encodeURIComponent(providerGroup)}&providerModelName=${encodeURIComponent(providerModelName)}`)
}

export const clearConversationMessagesApi = async (cid: number): Promise<void> => {
  return request.post(`/api/message/clear?cid=${cid}`)
}

export const resetConversationMessagesApi = async (cid: number, messageId: number): Promise<void> => {
  return request.post(`/api/message/reset?cid=${cid}&messageId=${messageId}`)
}

export const sendMessageApi = async (cid: number, data: { text: string; attachments?: string; agentId?: string }): Promise<void> => {
  return request.post(`/api/message/send?cid=${cid}`, data)
}

export const retryMessageApi = async (cid: number, messageId: number, data: { agentId?: string }): Promise<void> => {
  return request.post(`/api/message/retry?cid=${cid}&messageId=${messageId}`, data)
}

export const getMessageDetailApi = async (messageId: number): Promise<Message> => {
  return request.get(`/api/message/detail?messageId=${messageId}`)
}

export const cancelConversationApi = async (cid: number): Promise<void> => {
  return request.post(`/api/conversation/cancel?id=${cid}`)
}

export const updateConversationConfigApi = async (cid: number, data: { permissionMode?: string }): Promise<void> => {
  return request.post(`/api/conversation/config?id=${cid}`, data)
}

export const approveConversationPermissionApi = async (cid: number, data: ApproveToolPayload): Promise<void> => {
  return request.post(`/api/message/approve?cid=${cid}`, data)
}

export const renameConversationApi = async (cid: number, title: string): Promise<void> => {
  return request.post(`/api/conversation/rename?id=${cid}&title=${encodeURIComponent(title)}`)
}

export const getConversationProcessesApi = async (cid: number): Promise<ActiveProcessInfo[]> => {
  return request.get(`/api/conversation/processes?id=${cid}`)
}

export const killConversationProcessApi = async (cid: number, toolCallId?: string): Promise<boolean> => {
  let url = `/api/conversation/processes/kill?id=${cid}`
  if (toolCallId) {
    url += `&toolCallId=${encodeURIComponent(toolCallId)}`
  }
  return request.post(url)
}

export const getConversationLlmCallsApi = async (cid: number): Promise<ActiveLlmCallInfo[]> => {
  return request.get(`/api/conversation/llm-calls?id=${cid}`)
}

/**
 * 查询指定会话对大模型可见消息的累计缓存占比（小数制，4 位小数，如 0.8765）
 */
export const getConversationCacheRatioApi = async (cid: number): Promise<number> => {
  return request.get(`/api/conversation/cache-ratio?id=${cid}`)
}
