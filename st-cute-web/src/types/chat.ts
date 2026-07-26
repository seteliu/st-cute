export interface ToolCall {
  id: string
  name: string
  args: string
  status: 'running' | 'success' | 'failed'
  resultSummary?: string
  hooks?: any[]
}

export interface Message {
  id: number
  role: 'user' | 'assistant' | 'system' | 'tool' | 'branch' | 'compressed'
  content: string
  thought?: string
  isStreaming?: boolean
  status?: 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'REJECTED' | 'SUCCESS' | 'FAILED' | 'CANCELED'
  parentMessageId?: number      // 关联的父消息 ID
  toolId?: string               // TOOL 专属展平属性
  toolName?: string             // TOOL 专属展平属性
  toolArguments?: string        // TOOL 专属展平属性
  hooks?: any[]                 // 工具关联的切面 Hook 状态日志
  beforeCompactContent?: string // 压缩前的完整大日志
  toolCalls?: string            // 助理关联的工具调用详情 JSON
  inputTokens?: number          // 输入 Token 消耗
  outputTokens?: number         // 输出 Token 消耗
  cachedTokens?: number         // 缓存命中 Token 消耗
  executionDurationMs?: number  // 物理生成耗时(毫秒)
  createdAt?: string            // 创建时间戳
}

export interface Conversation {
  id: number
  title: string
  projectId?: number
  providerGroup?: string
  providerModelName?: string
  permissionMode?: string
  updatedAt: string
  parentCid?: number | null
  inputTokens?: number
  outputTokens?: number
  cachedTokens?: number
  loopRunning?: number
  iterationCount?: number
  waitingToolIds?: string[]
  waitingSubCids?: number[]
}

export interface Project {
  id: number
  name: string
  path: string
  createdAt?: string
}

export interface LimitMessageDto {
  messages: Message[]
  truncated: boolean
}
