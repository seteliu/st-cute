import request from '@/utils/request'

/**
 * 一键获取当前会话关联的完整环境上下文信息 (Skills, Hooks, MCP, Tokens)
 */
export const getContextInfoApi = async (cid: number): Promise<any> => {
  return request.get(`/api/agent-context/info?cid=${cid}`)
}

/**
 * 一键热重载当前会话专属项目的环境资产
 */
export const reloadContextAssetsApi = async (cid: number): Promise<boolean> => {
  return request.post(`/api/agent-context/reload?cid=${cid}`)
}
