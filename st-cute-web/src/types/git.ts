export interface GitBranch {
  path: string
  branch: string
  /** 是否为仓库当前检出分支（git branch 的 * 标记），默认选中用 */
  current?: boolean
}

export interface FileDiff {
  filename: string
  diffContent: string
  changeType?: 'ADD' | 'MODIFY' | 'DELETE'
}
