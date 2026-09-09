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
  role: 'user' | 'assistant' | 'system' | 'tool' | 'branch' | 'compressed' | 'folded'
  content: string
  thought?: string
  status?: 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'REJECTED' | 'SUCCESS' | 'FAILED' | 'CANCELED'
  parentMessageId?: number      // 关联的父消息 ID
  toolId?: string               // TOOL 专属展平属性
  toolName?: string             // TOOL 专属展平属性
  toolArguments?: string        // TOOL 专属展平属性
  hooks?: any[]                 // 工具关联的切面 Hook 状态日志
  toolCalls?: string            // 助理关联的工具调用详情 JSON
  inputTokens?: number          // 输入 Token 消耗
  outputTokens?: number         // 输出 Token 消耗
  cachedTokens?: number         // 缓存命中 Token 消耗
  executionDurationMs?: number  // 物理生成耗时(毫秒)
  attachments?: string          // 消息附件元数据 JSON 字符串
  createTime?: string           // 创建时间戳
  updateTime?: string           // 更新时间戳
  foldedMinId?: number          // FOLDED 专属：折叠区间最小消息 ID（闭区间）
  foldedMaxId?: number          // FOLDED 专属：折叠区间最大消息 ID（闭区间）
  assistantCount?: number       // FOLDED 专属：折叠的助手消息数量
  toolCount?: number            // FOLDED 专属：折叠的工具消息数量
  errorDetails?: FoldErrorDetail[] // FOLDED 专属：折叠明细中的失败/异常结构化列表（非空即有异常）
}

/** FOLDED 折叠明细中的异常结构化条目（与后端 MessageVo.ErrorDetail 对齐） */
export interface FoldErrorDetail {
  kind: 'tool' | 'assistant'
  toolName?: string
}


export interface Conversation {
  id: number
  title: string
  workspaceId?: string
  providerGroup?: string
  providerModelName?: string
  permissionMode?: string
  updateTime: string
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
  createTime?: string
  updateTime?: string
  expanded?: boolean
  active?: boolean
}

export interface LimitMessageDto {
  messages: Message[]
  truncated: boolean
}

/** 流式切片推送载荷契约（与后端 StreamChunkPayload 对齐） */
export interface StreamChunkPayload {
  id: number | string
  text: string
}

