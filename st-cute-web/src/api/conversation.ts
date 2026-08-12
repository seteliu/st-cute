import request from '@/utils/request'
import { Message, Conversation, LimitMessageDto } from '@/types'

export const getConversations = async (): Promise<Conversation[]> => {
  return request.get('/api/conversation/list')
}

export const getConversationMessages = async (cid: number): Promise<LimitMessageDto> => {
  return request.get(`/api/message/list?cid=${cid}`)
}

export const deleteConversationById = async (cid: number): Promise<any> => {
  return request.delete(`/api/conversation/delete?id=${cid}`)
}

export const batchDeleteConversationsApi = async (ids: number[]): Promise<any> => {
  return request.post('/api/conversation/batch-delete', ids)
}

export const createConversationApi = async (conversation: Partial<Conversation>): Promise<Conversation> => {
  return request.post('/api/conversation/create', conversation)
}

export const updateConversationProviderApi = async (cid: number, providerGroup: string, providerModelName: string): Promise<any> => {
  return request.post(`/api/conversation/update-provider?id=${cid}&providerGroup=${encodeURIComponent(providerGroup)}&providerModelName=${encodeURIComponent(providerModelName)}`)
}

export const clearConversationMessagesApi = async (cid: number): Promise<any> => {
  return request.post(`/api/message/clear?cid=${cid}`)
}

export const resetConversationMessagesApi = async (cid: number, messageId: number): Promise<void> => {
  return request.post(`/api/message/reset?cid=${cid}&messageId=${messageId}`)
}

export const sendMessageApi = async (cid: number, data: { text: string; agentId?: string }): Promise<void> => {
  return request.post(`/api/message/send?cid=${cid}`, data)
}

export const retryMessageApi = async (cid: number, messageId: number, data: { agentId?: string }): Promise<void> => {
  return request.post(`/api/message/retry?cid=${cid}&messageId=${messageId}`, data)
}

export const getMessageDetailApi = async (messageId: number): Promise<Message> => {
  return request.get(`/api/message/detail?messageId=${messageId}`)
}

export const cancelConversationApi = async (cid: number): Promise<any> => {
  return request.post(`/api/conversation/cancel?id=${cid}`)
}

export const updateConversationConfigApi = async (cid: number, data: { permissionMode?: string }): Promise<any> => {
  return request.post(`/api/conversation/config?id=${cid}`, data)
}

export const approveConversationPermissionApi = async (
  cid: number,
  data: {
    id: string
    decision: 'ALLOW' | 'DENY' | 'REJECTED'
    alwaysAllow?: boolean
    toolName?: string
    contentPattern?: string
    customArgOverride?: string
  }
): Promise<any> => {
  return request.post(`/api/message/approve?cid=${cid}`, data)
}

export const renameConversationApi = async (cid: number, title: string): Promise<void> => {
  return request.post(`/api/conversation/rename?id=${cid}&title=${encodeURIComponent(title)}`)
}

export interface ActiveProcessInfo {
  cid: number
  sessionTitle: string
  toolCallId: string
  pid: number
  command: string
  cwd: string
  startTime: number
  runningTimeMs: number
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

export interface ActiveLlmCallInfo {
  cid: number
  sessionTitle: string
  llmCallId: string
  model: string
  startTime: number
  durationTimeMs: number
}

export const getConversationLlmCallsApi = async (cid: number): Promise<ActiveLlmCallInfo[]> => {
  return request.get(`/api/conversation/llm-calls?id=${cid}`)
}
