import request from '@/utils/request'
import { Skill, Hook, McpServer, AgentRule, AgentContextInfo } from '@/types'

/**
 * 一键获取当前会话关联的完整环境上下文信息 (Skills, Hooks, MCP, Tokens)
 */
// silent=true 标识后台静默请求（断线重连强刷场景）：失败时拦截器不弹全局错误提示，由调用方兜底
export const getContextInfoApi = async (cid: number, silent = false): Promise<AgentContextInfo> => {
  return request.get(`/api/agent-context/info?cid=${cid}`, { silent })
}

/**
 * 一键热重载当前会话专属项目的环境资产
 */
export const reloadContextAssetsApi = async (cid: number): Promise<boolean> => {
  return request.post(`/api/agent-context/reload?cid=${cid}`)
}
