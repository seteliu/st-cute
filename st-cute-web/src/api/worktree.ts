import request from '@/utils/request'
import { Worktree, FileDiff } from '@/types'

export const getWorktrees = async (cid?: number): Promise<Worktree[]> => {
  return request.get('/api/worktree/list' + (cid ? `?cid=${cid}` : ''))
}

export const getWorktreeDiff = async (branchName: string, cid?: number): Promise<FileDiff[]> => {
  return request.get(`/api/worktree/diff?branchName=${encodeURIComponent(branchName)}` + (cid ? `&cid=${cid}` : ''))
}
