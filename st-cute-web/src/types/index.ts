export interface Result<T = any> {
  code: number
  msg: string
  data: T
}

export * from './chat'
export * from './git'
export * from './provider'
export * from './agent'
export * from './project'
export * from './user'
