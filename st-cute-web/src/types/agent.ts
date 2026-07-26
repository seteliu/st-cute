import { Message } from './chat'

export interface McpTool {
  name: string
  description: string
  inputSchema?: any
}

export interface McpServer {
  name: string
  status: string
  type: string
  tools?: McpTool[]
}

export interface Skill {
  name: string
  description?: string
  systemPrompt?: string
  command?: string
  source: 'PROJECT' | 'GLOBAL'
  path: string
  tools?: string[]
}

export interface Hook {
  name: string
  blocking: boolean
  event: string
  args?: {
    command?: string
    [key: string]: any
  }
  toolFilter?: string
  pattern?: string
}

export interface SubAgent {
  role: string
  typeName: string
  workspace: string
  cid: string
  parentCid: string
  status: 'running' | 'success' | 'failed'
  messages: Message[]
  currentIteration: number
  inputTokens: number
  outputTokens: number
  cachedTokens?: number
  truncated?: boolean
  pendingPermissionReq?: {
    id: string
    toolName: string
    arguments: string
    isEditingArgs?: boolean
    editedArgumentsJson?: string
  }
}

export interface AgentRule {
  name: string
  path: string
  updateTime: string
  size: number
  content: string
}
