import { computed } from 'vue'
import { useConversationStore } from '@/stores/conversation'
import { useProviderStore } from '@/stores/provider'

/**
 * token 数量展示格式化：≥1000 折算为 K 计（如 117623 → 117.6K、2000 → 2K），
 * 小数保留 1 位四舍五入，整除时不含小数位；不足 1000 原样展示。
 */
export function formatTokenCount(value: number): string {
  if (value >= 1000) {
    const rounded = Math.round((value / 1000) * 10) / 10
    return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)}K`
  }
  return `${value}`
}

/**
 * 上下文窗口共享口径（主会话 / 子 Agent / 概览页三处弹层统一取值）。
 *
 * 窗口上限恒走主会话：后端 cascadeProviderToChildren 已保证子会话继承父会话的供应商绑定，
 * 以当前活动主会话的 providerGroup → providerList → contextSize 反查，口径与实际生效窗口一致。
 * 查无（未绑定供应商 / 配置缺失）时 contextLimit 为 null，调用方据此隐藏「窗口」展示行。
 */
export function useContextWindow() {
  const conversationStore = useConversationStore()
  const providerStore = useProviderStore()

  /** 窗口上限（token 数）：主会话绑定供应商的 contextSize，未绑定或配置缺失时为 null */
  const contextLimit = computed<number | null>(() => {
    const activeCid = conversationStore.activeCid
    if (activeCid === null || activeCid === undefined) return null
    const activeConv = conversationStore.conversationList.find(s => s.id === activeCid)
    if (activeConv && activeConv.providerGroup) {
      const provider = providerStore.providerList.find(p => p.group === activeConv.providerGroup)
      if (provider && provider.contextSize) {
        return provider.contextSize
      }
    }
    return null
  })

  /** 窗口上限展示文本：千位以上折算 K（如 1000K），未就绪时为 null */
  const contextLimitText = computed<string | null>(() => {
    const limit = contextLimit.value
    if (limit === null) return null
    return limit >= 1000 ? `${(limit / 1000).toFixed(0)}K` : `${limit}`
  })

  /** 用量占窗口的百分比文本（1 位小数），窗口未就绪时为 null */
  const usagePercentage = computed<(total: number) => string | null>(() => {
    return (total: number) => {
      const limit = contextLimit.value
      if (limit === null || !limit) return null
      return ((total / limit) * 100).toFixed(1)
    }
  })

  return {
    contextLimit,
    contextLimitText,
    usagePercentage
  }
}
