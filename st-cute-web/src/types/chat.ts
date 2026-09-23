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

/** 聊天输入框的暂存附件结构（按会话吸附的草稿附件，暂存于前端 store，发送时随消息一并提交） */
export interface StagedFile {
  id: string
  file: File
  name: string
  size: number
  isImage: boolean
  previewUrl?: string
  status: 'idle' | 'uploading' | 'success' | 'error'
  uploadedPath?: string
  mimeType?: string
}

export interface LimitMessageDto {
  messages: Message[]
  truncated: boolean
}

/** 工具审批决策请求体（与后端 ApproveToolDto 对齐） */
export interface ApproveToolPayload {
  /** 待审批的工具调用 ID */
  id: string
  /** 决策：允许 / 拒绝本次 / 已被用户在别处拒绝 */
  decision: 'ALLOW' | 'DENY' | 'REJECTED'
  /** 本次允许且记住规则（后续同类调用免审批） */
  alwaysAllow?: boolean
  toolName?: string
  contentPattern?: string
  customArgOverride?: string
}

/** 活跃系统进程信息（与后端 ActiveProcessVo 对齐） */
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

/** 活跃大模型调用信息（与后端 ActiveLlmCallVo 对齐） */
export interface ActiveLlmCallInfo {
  cid: number
  sessionTitle: string
  llmCallId: string
  model: string
  startTime: number
  durationTimeMs: number
}

/** 流式切片语义类型（与后端 StreamChunkType 对齐）：null/缺省=常规增量内容，CLEAR=清空重放 */
export type StreamChunkType = 'CLEAR'

/** 流式切片推送载荷契约（与后端 StreamChunkPayload 对齐） */
export interface StreamChunkPayload {
  /** 目标消息 ID：思考流/正文流为助手消息 ID，工具日志流为工具消息 ID（三条流统一按消息 ID 归属） */
  id: number
  text: string
  /** 切片语义类型：缺省表示常规增量；CLEAR 表示透明重试重放前发出、需丢弃已累积内容 */
  type?: StreamChunkType | null
}

