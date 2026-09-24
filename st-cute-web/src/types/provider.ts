export interface Provider {
  group: string
  protocol: string
  baseUrl: string
  useFullUrl?: boolean
  apiKey: string
  modelName: string
  temperature?: number | null
  contextSize?: number
  maxTokens?: number | null
  reasoningEffort?: string | null
  multimodal?: boolean
  active?: boolean
}

/** 基础配置项（与后端 BasicConfigDto 对齐） */
export interface BasicConfig {
  language?: 'zh-CN' | 'en-US'
  theme?: 'dark' | 'light'
  newlineKey: 'enter' | 'alt+enter'
  httpLog: boolean
  httpLogDays: number
  /** 是否记录响应部分（含 SSE 流式响应全文）：关闭后仅记录请求报文与异常，默认开启 */
  httpLogIncludeResponse?: boolean
  /** 是否已设置安全密码（查询接口回传的状态标记；密码本身全链路不回传） */
  passwordSet?: boolean
  maxViewHistoryLimit?: number
  pathSandboxEnabled?: boolean
  minimalSkillMode?: boolean
}

/** 安全密码操作请求体（设置/修改/清除，与后端口载荷对齐） */
export interface PasswordPayload {
  /** 原文密码的 SHA-256 传输摘要（清除时可不传） */
  password?: string
  /** password 摘要对应的原文长度：随摘要附带，供后端兜底校验复杂度策略 */
  passwordLength?: number
  /** 显式清除标记：true 时清除密码（优先级高于 password） */
  passwordClear?: boolean
}

