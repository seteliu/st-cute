<template>
  <div class="chat-footer">
    <div class="input-area">
      <n-input
        ref="inputInstRef"
        v-model:value="appStore.userInput"
        type="textarea"
        :placeholder="inputPlaceholder"
        :disabled="isInputDisabled"
        :bordered="false"
        :autosize="{ minRows: 2, maxRows: 10 }"
        @keydown.capture="handleEnterKey"
        maxlength="50000"
      />
      <!-- 桌面端操作区域 -->
      <div v-if="!isMobile" class="input-actions" style="margin-top: 8px;">
        <div class="input-configs" style="display: flex; align-items: center; gap: 12px;">
          <!-- 供应商选择 -->
          <n-select
            v-if="conversationStore.activeCid"
            :value="activeConversationProviderValue"
            :options="providerOptions"
            :placeholder="t('sider.groupSelect')"
            :disabled="appStore.loopRunning"
            size="small"
            class="provider-select"
            @update:value="handleProviderChange"
          />
          
          <!-- 权限安全级别 -->
          <div class="permission-select-wrapper" style="display: flex; align-items: center; gap: 4px;">
            <n-select
              :value="appStore.permissionMode"
              :options="appStore.permissionModeOptions"
              :disabled="appStore.loopRunning"
              @update:value="appStore.handlePermissionModeChange"
              size="small"
              class="permission-select"
            />
            <n-tooltip trigger="hover" placement="top">
              <template #trigger>
                <div class="permission-help-tooltip" style="cursor: help; color: #767680; display: flex; align-items: center;">
                  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                    <line x1="12" y1="17" x2="12.01" y2="17"></line>
                  </svg>
                </div>
              </template>
              <div style="font-size: 0.8rem; line-height: 1.6; padding: 4px; color: #e3e3e7; max-width: 260px;">
                <div style="font-weight: bold; border-bottom: 1px solid #444; margin-bottom: 6px; padding-bottom: 4px;">{{ t('sider.permissionMode') }}:</div>
                <div><strong>{{ t('sider.modeReadOnly') }}: </strong>{{ t('sider.modeReadOnlyTooltip') }}</div>
                <div style="margin-top: 4px;"><strong>{{ t('sider.modeSmart') }}: </strong>{{ t('sider.modeSmartTooltip') }}</div>
                <div style="margin-top: 4px;"><strong>{{ t('sider.modeAllAllow') }}: </strong>{{ t('sider.modeAllAllowTooltip') }}</div>
              </div>
            </n-tooltip>
          </div>
        </div>

        <div class="input-buttons" style="display: flex; gap: 8px;">
          <n-button
            type="primary"
            :class="{ 'send-btn-active': !appStore.loopRunning && appStore.userInput && !isInputDisabled }"
            :disabled="!appStore.loopRunning && (!appStore.userInput || isInputDisabled)"
            @click="appStore.loopRunning ? appStore.cancelLoop() : conversationStore.sendUserMsg()"
          >
            <template v-if="appStore.loopRunning" #icon>
              <span style="display: inline-flex; align-items: center; gap: 4px;">
                <svg class="spin-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="12" y1="2" x2="12" y2="6"></line>
                  <line x1="12" y1="18" x2="12" y2="22"></line>
                  <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"></line>
                  <line x1="16.24" y1="16.24" x2="19.07" y2="19.07"></line>
                  <line x1="2" y1="12" x2="6" y2="12"></line>
                  <line x1="18" y1="12" x2="22" y2="12"></line>
                  <line x1="4.93" y1="19.07" x2="7.76" y2="16.24"></line>
                  <line x1="16.24" y1="7.76" x2="19.07" y2="4.93"></line>
                </svg>
              </span>
            </template>
            {{ appStore.loopRunning ? t('chat.cancelLoop') : t('chat.send') }}
          </n-button>
        </div>
      </div>

      <!-- 移动端操作区域 -->
      <div v-else class="input-actions-mobile" style="margin-top: 8px; display: flex; flex-direction: column; gap: 8px; width: 100%;">
        <!-- 第一排：供应商大模型选择 (占满 100%) -->
        <div v-if="conversationStore.activeCid" style="width: 100%;">
          <n-select
            :value="activeConversationProviderValue"
            :options="providerOptions"
            :placeholder="t('sider.groupSelect')"
            :disabled="appStore.loopRunning"
            size="small"
            style="width: 100%;"
            @update:value="handleProviderChange"
          />
        </div>
        
        <!-- 第二排：换行按钮 + 权限模式 (flex: 1 撑满) + 发送按钮 (避免误触) -->
        <div style="display: flex; gap: 8px; align-items: center; width: 100%;">
          <n-button
            secondary
            size="small"
            :disabled="isInputDisabled"
            @click="insertNewline"
          >
            Enter
          </n-button>
          
          <div style="flex: 1;">
            <n-select
              :value="appStore.permissionMode"
              :options="appStore.permissionModeOptions"
              :disabled="appStore.loopRunning"
              @update:value="appStore.handlePermissionModeChange"
              size="small"
              style="width: 100%;"
            />
          </div>

          <n-button
            type="primary"
            size="small"
            :class="{ 'send-btn-active': !appStore.loopRunning && appStore.userInput && !isInputDisabled }"
            :disabled="!appStore.loopRunning && (!appStore.userInput || isInputDisabled)"
            @click="appStore.loopRunning ? appStore.cancelLoop() : conversationStore.sendUserMsg()"
          >
            <template v-if="appStore.loopRunning" #icon>
              <span style="display: inline-flex; align-items: center; gap: 4px;">
                <svg class="spin-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="12" y1="2" x2="12" y2="6"></line>
                  <line x1="12" y1="18" x2="12" y2="22"></line>
                  <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"></line>
                  <line x1="16.24" y1="16.24" x2="19.07" y2="19.07"></line>
                  <line x1="2" y1="12" x2="6" y2="12"></line>
                  <line x1="18" y1="12" x2="22" y2="12"></line>
                  <line x1="4.93" y1="19.07" x2="7.76" y2="16.24"></line>
                  <line x1="16.24" y1="7.76" x2="19.07" y2="4.93"></line>
                </svg>
              </span>
            </template>
            {{ appStore.loopRunning ? t('chat.cancelLoop') : t('chat.send') }}
          </n-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { useResponsive } from '@/utils/useResponsive'

const { isMobile } = useResponsive()
import { useConversationStore } from '@/stores/conversation'
import { useProjectStore } from '@/stores/project'
import { useProviderStore } from '@/stores/provider'
import { updateConversationProviderApi } from '@/api/conversation'
import { t } from '@/i18n'

const appStore = useAppStore()
const conversationStore = useConversationStore()
const projectStore = useProjectStore()
const providerStore = useProviderStore()

const inputInstRef = ref<any>(null)

const insertNewline = () => {
  const textarea = inputInstRef.value?.textareaElRef
  if (textarea) {
    const start = textarea.selectionStart
    const end = textarea.selectionEnd
    const text = appStore.userInput || ''
    appStore.userInput = text.substring(0, start) + '\n' + text.substring(end)
    nextTick(() => {
      textarea.selectionStart = textarea.selectionEnd = start + 1
      textarea.focus()
    })
  } else {
    appStore.userInput = (appStore.userInput || '') + '\n'
  }
}

// 监听活跃会话发生改变，自动聚焦输入框
watch(
  () => conversationStore.activeCid,
  (newVal) => {
    if (newVal) {
      nextTick(() => {
        if (!isInputDisabled.value) {
          inputInstRef.value?.focus()
        }
      })
    }
  }
)

const isInputDisabled = computed(() => {
  return (
    appStore.loopRunning ||
    projectStore.projectList.length === 0 ||
    !projectStore.activeProjectId ||
    !conversationStore.activeCid ||
    providerStore.providerList.length === 0
  )
})

const inputPlaceholder = computed(() => {
  if (projectStore.projectList.length === 0) {
    return t('chat.inputPlaceholderNoProject')
  }
  if (!projectStore.activeProjectId) {
    return t('chat.inputPlaceholderNoActiveProject')
  }
  if (providerStore.providerList.length === 0) {
    return t('chat.inputPlaceholderNoProvider')
  }
  if (!conversationStore.activeCid) {
    return t('chat.inputPlaceholderNoCid')
  }
  if (appStore.newlineKey === 'alt+enter') {
    return t('chat.inputPlaceholderAltEnter')
  }
  return t('chat.inputPlaceholderEnter')
})


const handleEnterKey = (e: KeyboardEvent) => {
  if (e.key !== 'Enter' || isInputDisabled.value) {
    return
  }

  // 1. 确定是否应当执行发送消息
  let shouldSend = false
  if (appStore.newlineKey === 'alt+enter') {
    if (e.altKey) {
      shouldSend = true
    }
  } else {
    // 默认配置（enter 发送）：只有在没有任何辅助键按下时才发送
    if (!e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey) {
      shouldSend = true
    }
  }

  // 2. 如果应当发送，则执行发送并阻止默认换行行为
  if (shouldSend) {
    e.preventDefault()
    e.stopPropagation()
    conversationStore.sendUserMsg()
  } else {
    // 3. 否则放行，让原生 textarea 换行逻辑正常处理
  }
}

const activeConversationProviderValue = computed(() => {
  const activeId = conversationStore.activeCid
  const sess = conversationStore.conversationList.find(s => s.id === activeId)
  if (sess && sess.providerGroup) {
    return `${sess.providerGroup}/${sess.providerModelName || ''}`
  }
  if (providerStore.providerList.length > 0) {
    const first = providerStore.providerList[0]
    return `${first.group}/${first.modelName || ''}`
  }
  return undefined
})

const providerOptions = computed(() => {
  const groups: Record<string, any[]> = {}
  providerStore.providerList.forEach(p => {
    const groupName = p.group || '默认供应商'
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

    // 联动更新直接子会话的 provider
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

</script>

<style scoped>
.spin-icon {
  animation: spin 1s linear infinite;
  display: block;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.send-btn-active {
  background: linear-gradient(135deg, #81b6e5 0%, #609bc5 100%) !important;
  border: none !important;
  box-shadow: 0 2px 8px rgba(129, 182, 229, 0.3);
  transition: transform 0.2s, box-shadow 0.2s;
}

.send-btn-active:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(129, 182, 229, 0.4);
}

.send-btn-active:active {
  transform: translateY(0);
}
</style>
