import request from '@/utils/request'
import { GitBranch, FileDiff } from '@/types'

// silent=true 标识后台静默请求（轮询场景）：失败时拦截器不弹全局错误提示，仅 reject 由调用方兜底
export const getBranches = async (cid?: number, silent = false): Promise<GitBranch[]> => {
  return request.get('/api/git/list' + (cid ? `?cid=${cid}` : ''), { silent })
}

export const getBranchDiff = async (branchName: string, cid?: number, silent = false): Promise<FileDiff[]> => {
  return request.get(`/api/git/diff?branchName=${encodeURIComponent(branchName)}` + (cid ? `&cid=${cid}` : ''), { silent })
}
