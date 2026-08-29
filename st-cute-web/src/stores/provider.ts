import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getProviders, saveProvider, deleteProvider } from '@/api/provider'
import { Provider } from '@/types'

export const useProviderStore = defineStore('provider', () => {
  const providerList = ref<Provider[]>([])
  const isEditing = ref(false)
  const originalModelName = ref('')
  
  const form = ref<Provider>({
    group: '',
    protocol: 'OPENAI',
    baseUrl: '',
    useFullUrl: false,
    apiKey: '',
    modelName: '',
    temperature: null,
    contextSize: undefined,
    maxTokens: null,
    reasoningEffort: '',
    multimodal: false
  })

  const protocolOptions = [
    { label: 'OpenAI Chat', value: 'OPENAI' },
    { label: 'Anthropic Claude', value: 'ANTHROPIC' }
  ]

  // 加载 Providers
  const loadProviders = async () => {
    try {
      const data = await getProviders()
      providerList.value = data
    } catch (e) {
      console.error('加载 Provider 失败:', e)
    }
  }

  // 保存 Provider
  const handleSaveProvider = async () => {
    if (
      !form.value.group ||
      !form.value.modelName ||
      form.value.contextSize === undefined || form.value.contextSize === null
    ) {
      if ((window as any).$message) {
        ;(window as any).$message.warning('请填写所有必要字段（名称、模型、窗口大小）')
      } else {
        console.warn('请填写必要字段')
      }
      return
    }

    try {
      await saveProvider(form.value, originalModelName.value)
      resetForm()
      await loadProviders()
    } catch (e) {
      console.error('保存 Provider 失败:', e)
    }
  }

  // 删除 Provider
  const handleDeleteProvider = async (group: string, modelName: string) => {
    try {
      await deleteProvider(group, modelName)
      await loadProviders()
    } catch (e) {
      console.error('删除 Provider 失败:', e)
    }
  }

  // 编辑 Provider
  const handleEditProvider = (prov: Provider) => {
    form.value = {
      ...prov,
      multimodal: Boolean(prov.multimodal)
    }
    originalModelName.value = prov.modelName
    isEditing.value = true
  }

  const resetForm = () => {
    form.value = {
      group: '',
      protocol: 'OPENAI',
      baseUrl: '',
      useFullUrl: false,
      apiKey: '',
      modelName: '',
      temperature: null,
      contextSize: undefined,
      maxTokens: null,
      reasoningEffort: '',
      multimodal: false
    }
    originalModelName.value = ''
    isEditing.value = false
  }

  return {
    providerList,
    isEditing,
    originalModelName,
    form,
    protocolOptions,
    loadProviders,
    handleSaveProvider,
    handleDeleteProvider,
    handleEditProvider,
    resetForm
  }
})
