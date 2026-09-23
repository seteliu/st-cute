import { Message } from '@/types'

/**
 * 消息规范化适配层（全局唯一收口点）。
 *
 * 后端 MessageVo.role 为大写枚举名（USER/ASSISTANT/...），前端类型与渲染
 * 统一使用小写字面量。历史上该转换散布在 conversation store、agent store、
 * Home.vue 三处以 `as any` 糊弄，现收归本函数，禁止单侧散写。
 *
 * 【对齐锚】后端 MessageVo.role（MessageRole 枚举序列化值）
 */
export const normalizeMessage = (msg: Message): Message => {
  if (!msg) return msg
  if (msg.role && msg.role !== msg.role.toLowerCase()) {
    return { ...msg, role: msg.role.toLowerCase() as Message['role'] }
  }
  return msg
}

/** 批量规范化消息列表（返回新数组，不修改入参） */
export const normalizeMessageList = (list: Message[] | undefined | null): Message[] => {
  if (!list) return []
  return list.map(normalizeMessage)
}
