<template>
  <!-- 添加项目弹窗 -->
  <n-modal
    :show="show"
    preset="card"
    style="width: 480px; max-width: 90vw; border: 1px solid var(--border-color); box-shadow: var(--shadow-overlay);"
    :title="t('sider.addProject')"
    size="medium"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div style="margin-top: 5px;">
      <n-form label-placement="left" label-width="110">
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.projectPath') }}
          </template>
          <n-input
            v-model:value="form.path"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.projectPathPlaceholder')"
            spellcheck="false"
            @input="onPathInput"
            @keydown.enter.prevent="submit"
            maxlength="255"
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.projectName') }}
          </template>
          <n-input
            v-model:value="form.name"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.projectNamePlaceholder')"
            spellcheck="false"
            @keydown.enter.prevent="submit"
            maxlength="50"
          />
        </n-form-item>
      </n-form>
    </div>
    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 12px;">
        <n-button type="primary" @click="submit">{{ t('common.confirm') }}</n-button>
        <n-button @click="emit('update:show', false)">{{ t('common.cancel') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useProjectStore } from '@/stores/project'
import { useConversationStore } from '@/stores/conversation'
import { t } from '@/i18n'

/**
 * 添加项目弹窗（原 LeftSider.vue 内联弹窗抽离）。
 * 提交成功后自动为新项目拉起第一个会话。
 */
const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const message = useMessage()
const projectStore = useProjectStore()
const conversationStore = useConversationStore()

const form = ref({ name: '', path: '' })
// 最近一次从路径提取的名称：名称未被用户手工改过时，路径变化自动跟随更新
const lastExtractedName = ref('')

// 每次打开重置表单
watch(() => props.show, (v) => {
  if (v) {
    form.value = { name: '', path: '' }
    lastExtractedName.value = ''
  }
})

const onPathInput = (val: string) => {
  if (!val) return
  // 去掉尾部斜杠后取最后一段作为默认项目名
  const cleaned = val.replace(/[\\/]+$/, '')
  const lastSlash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'))
  const dirName = lastSlash !== -1 ? cleaned.substring(lastSlash + 1) : cleaned

  if (!form.value.name || form.value.name === lastExtractedName.value) {
    form.value.name = dirName
    lastExtractedName.value = dirName
  }
}

const submit = async () => {
  if (!form.value.path.trim()) {
    message.warning('请输入项目路径')
    return false
  }
  try {
    const saved = await projectStore.handleSaveProject({
      name: form.value.name.trim() || '未命名项目',
      path: form.value.path.trim()
    })
    emit('update:show', false)
    // 自动为新创建的项目拉起第一个会话，提供极佳的流程体验
    await conversationStore.createConversation(saved.id)
    return true
  } catch (e: any) {
    message.error(e.response?.data?.message || e.message || '保存项目失败')
    return false
  }
}
</script>
