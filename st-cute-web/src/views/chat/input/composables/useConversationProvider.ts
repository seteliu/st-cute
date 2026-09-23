import { computed, ref, watch } from 'vue'
import { useConversationStore } from '@/stores/conversation'
import { useProviderStore } from '@/stores/provider'
import { updateConversationProviderApi } from '@/api/conversation'
import { t } from '@/i18n'

export function useConversationProvider() {
  const conversationStore = useConversationStore()
  const providerStore = useProviderStore()

  // 当前会话实际生效的供应商（严格精确匹配，展示层降级）：
  // 会话绑定的 group+modelName 在列表中命中则用之；失效或未绑定则降级取列表第一个；列表为空为 null
  const effectiveProvider = computed(() => {
    const list = providerStore.providerList
    if (!list || list.length === 0) return null
    const activeId = conversationStore.activeCid
    const sess = conversationStore.conversationList.find(s => s.id === activeId)
    if (sess && sess.providerGroup) {
      const bound = list.find(p => p.group === sess.providerGroup && p.modelName === sess.providerModelName)
      if (bound) return bound
    }
    return list[0]
  })

  const activeConversationProviderValue = computed(() => {
    const eff = effectiveProvider.value
    if (!eff) return undefined
    return `${eff.group}/${eff.modelName || ''}`
  })

  const activeProvider = computed(() => {
    return effectiveProvider.value
  })

  // 当前选中的模型是否开启了多模态支持
  const isMultimodal = computed(() => {
    return Boolean(activeProvider.value?.multimodal)
  })

  const providerOptions = computed(() => {
    const groups: Record<string, any[]> = {}
    providerStore.providerList.forEach(p => {
      const groupName = p.group || t('chat.defaultProvider')
      if (!groups[groupName]) {
        groups[groupName] = []
      }
      groups[groupName].push({
        label: p.modelName,
        value: `${p.group}/${p.modelName}`
      })
    })

    return Object.entries(groups).map(([groupName, children]) => ({
      type: 'group',
      label: groupName,
      key: groupName,
      children: children
    }))
  })

  const handleProviderChange = async (val: string) => {
    if (!conversationStore.activeCid) return
    const [group, modelName] = val.split('/')
    try {
      await updateConversationProviderApi(conversationStore.activeCid, group, modelName || '')
      
      const activeId = conversationStore.activeCid
      const sess = conversationStore.conversationList.find(s => s.id === activeId)
      if (sess) {
        sess.providerGroup = group
        sess.providerModelName = modelName || ''
      }

      conversationStore.conversationList.forEach(s => {
        if (s.parentCid === activeId) {
          s.providerGroup = group
          s.providerModelName = modelName || ''
        }
      })
    } catch (e) {
      console.error('更新会话供应商失败:', e)
    }
  }

  // 会话供应商失效检测与降级写回：
  // 会话绑定的供应商（group+modelName）在列表中已不存在时，下拉展示层会自动降级为列表第一个；
  // 此处监听相关数据源，在检测到失效且列表非空时，把降级结果调用接口持久化写回会话
  // （幂等去抖：同一会话同一目标供应商只写回一次；禁止在 computed 内发请求，故由此 watch 驱动）
  const providerWriteBackKey = ref('')
  watch(
    [
      () => conversationStore.activeCid,
      () => conversationStore.conversationList.map(s => [s.id, s.providerGroup, s.providerModelName]),
      () => providerStore.providerList
    ],
    async () => {
      const activeId = conversationStore.activeCid
      if (!activeId) return
      const list = providerStore.providerList
      if (!list || list.length === 0) return
      const sess = conversationStore.conversationList.find(s => s.id === activeId)
      if (!sess) return

      // 幂等去抖：同一会话已写回到同一目标则跳过，避免 watch 抖动或接口返回前的重复写回
      const eff = effectiveProvider.value
      if (!eff) return
      const writeKey = `${activeId}:${eff.group}/${eff.modelName || ''}`
      if (providerWriteBackKey.value === writeKey && sess.providerGroup === eff.group && sess.providerModelName === eff.modelName) {
        return
      }

      // 仅在"绑定失效或未绑定"时才写回（绑定本身就指向 eff 的情况无需动作）
      const boundExists = sess.providerGroup && list.some(p => p.group === sess.providerGroup && p.modelName === sess.providerModelName)
      if (boundExists) {
        providerWriteBackKey.value = ''
        return
      }
      if (sess.providerGroup === eff.group && sess.providerModelName === eff.modelName) {
        return
      }

      providerWriteBackKey.value = writeKey
      try {
        await updateConversationProviderApi(activeId, eff.group, eff.modelName || '')
        // 本地同步当前会话（后端接口会联动子会话，这里只同步主会话展示即可）
        sess.providerGroup = eff.group
        sess.providerModelName = eff.modelName || ''
        console.log(`会话 ${activeId} 绑定的供应商已失效，已降级并写回为 ${eff.group}/${eff.modelName}`)
      } catch (e) {
        console.error('降级写回会话供应商失败:', e)
        providerWriteBackKey.value = ''
      }
    },
    { immediate: true, deep: true }
  )

  return {
    effectiveProvider,
    activeConversationProviderValue,
    activeProvider,
    isMultimodal,
    providerOptions,
    handleProviderChange
  }
}
