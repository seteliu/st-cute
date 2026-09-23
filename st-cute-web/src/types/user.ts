/** 用户基础信息（与后端 AuthController 用户信息载荷对齐） */
export interface UserInfo {
  username: string
  role: string
}

/** 登录质询响应（与后端 ChallengeDto 对齐）：服务端签发的一次性质询值与计算存储摘要所需的盐（未配置访问码时为 null） */
export interface ChallengeInfo {
  /** 一次性质询值（64 位十六进制随机数，服务端侧 5 分钟内单次有效） */
  nonce: string
  /** 存储值的盐段（Base64），用于重算存储摘要以计算证明摘要 */
  salt: string
}
