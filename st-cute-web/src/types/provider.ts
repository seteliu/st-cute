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
  active?: boolean
}
