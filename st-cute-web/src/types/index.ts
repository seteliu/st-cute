export interface Result<T = any> {
  code: number
  msg: string
  data: T
}

export * from './chat'
export * from './worktree'
export * from './provider'
export * from './agent'

