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
}

export interface AgentRule {
  name: string
  path: string
  updateTime: string
  size: number
  content: string
}

/** 会话环境上下文聚合信息（与后端 AgentContextController info 接口载荷对齐） */
export interface AgentContextInfo {
  /** 权限安全模式（STRICT_APPROVAL / RELAXED_APPROVAL / ALL_ALLOW） */
  permissionMode?: string
  /** 输入 Token 累计用量 */
  inputTokens?: number
  /** 输出 Token 累计用量 */
  outputTokens?: number
  /** 缓存命中 Token 累计用量 */
  cachedTokens?: number
  /** 会话循环是否运行中（0=停止 1=运行；后端个别场景可能回传 boolean） */
  loopRunning?: number | boolean
  /** 当前迭代轮数 */
  iterationCount?: number
  /** 当前会话可见的技能清单 */
  skills?: Skill[]
  /** 当前会话生效的 Hook 清单 */
  hooks?: Hook[]
  /** 当前会话挂载的 MCP 服务状态清单 */
  mcpServers?: McpServer[]
  /** 当前会话生效的规则文件清单 */
  rules?: AgentRule[]
}

/** slash 补全分组结构（与后端 SlashGroupVo 对齐） */
export interface SlashGroupItem {
  /** 分组名称（如 skill） */
  group: string
  /** 分组下的补全选项 */
  items: SlashItem[]
}

/** slash 补全单选项结构（与后端 SlashItemVo 对齐） */
export interface SlashItem {
  /** 选项唯一名称（如技能名） */
  name: string
  /** 选项描述说明 */
  description: string
}
