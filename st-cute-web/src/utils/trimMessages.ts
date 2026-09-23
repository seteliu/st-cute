import { Message } from '@/types'

/**
 * 前端视图保留上限的消息列表裁剪（原实现位于 stores/conversation.ts 尾部，
 * 因 agent store 也需要复用而抽出，消除 store 间横向依赖）。
 *
 * 裁剪策略：超出 limit 时从「目标保留数对应的下标」向前回溯最近一条 USER 消息，
 * 以 USER 消息为界整段保留（不切断一轮对话），找不到 USER 边界时放弃裁剪。
 */
export function trimMessagesArray(messages: any[], limit: number): { list: any[]; truncated: boolean } {
  if (messages.length <= limit) {
    return { list: messages, truncated: false }
  }

  const total = messages.length
  let targetIndex = total - limit
  let startIndex = 0

  while (targetIndex >= 0) {
    const msg = messages[targetIndex]
    if (msg && (msg.role === 'user' || msg.role === 'USER')) {
      startIndex = targetIndex
      break
    }
    targetIndex--
  }

  if (startIndex > 0) {
    return { list: messages.slice(startIndex), truncated: true }
  }
  return { list: messages, truncated: false }
}
