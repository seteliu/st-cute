export interface Worktree {
  path: string
  branch: string
}

export interface FileDiff {
  filename: string
  diffContent: string
  changeType?: 'ADD' | 'MODIFY' | 'DELETE'
}
