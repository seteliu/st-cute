import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getProviders, saveProvider, deleteProvider } from '@/api/provider'
import { Provider } from '@/types'

export const useProviderStore = defineStore('provider', () => {
  const providerList = ref<Provider[]>([])
  const isEditing = ref(false)
  const originalModelName = ref('')
  // 编辑前记录的原始分组名：与 originalModelName 联合定位待更新条目（支持编辑时修改分组名称）
  const originalGroup = ref('')
  // 复制模式标识：非空表示当前表单由"复制"进入，值为源条目的模型名（保存时走新增，且强制改名）
  const copySourceModelName = ref('')

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
    { label: 'OpenAI Response', value: 'OPENAI_RESPONSE' },
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
      await saveProvider(form.value, originalGroup.value, originalModelName.value)
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
    originalGroup.value = prov.group
    copySourceModelName.value = ''
    isEditing.value = true
  }

  // 复制 Provider：以源条目全字段预填表单，模型名追加 -copy 后缀并强制用户修改；
  // 保存时走新增（不带 originalModelName），分组、协议、端点、密钥等其余字段原样继承
  const handleCopyProvider = (prov: Provider) => {
    form.value = {
      ...prov,
      modelName: `${prov.modelName}-copy`,
      multimodal: Boolean(prov.multimodal)
    }
    originalModelName.value = ''
    originalGroup.value = ''
    copySourceModelName.value = prov.modelName
    isEditing.value = false
  }

  // 同组同名查重助手：忽略大小写比对同分组下是否存在相同模型名称的条目
  // excludeModelName：需要排除自身的模型名（编辑模式传入当前正在编辑的模型名）
  const isModelNameDuplicated = (group: string, modelName: string, excludeModelName?: string): boolean => {
    return providerList.value.some(p =>
      p.group.toLowerCase() === group.toLowerCase() &&
      p.modelName.toLowerCase() === modelName.toLowerCase() &&
      p.modelName.toLowerCase() !== (excludeModelName || '').toLowerCase()
    )
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
    originalGroup.value = ''
    copySourceModelName.value = ''
    isEditing.value = false
  }

  return {
    providerList,
    isEditing,
    originalModelName,
    originalGroup,
    copySourceModelName,
    form,
    protocolOptions,
    loadProviders,
    handleSaveProvider,
    handleDeleteProvider,
    handleEditProvider,
    handleCopyProvider,
    isModelNameDuplicated,
    resetForm
  }
})
