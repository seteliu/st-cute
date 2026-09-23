<template>
  <div
    class="chat-footer"
    :class="{ 'dragging-active': isDragging && isMultimodal }"
    @dragover.prevent="handleDragOver"
    @dragleave="handleDragLeave"
    @drop.prevent="handleDrop"
    @paste="handlePaste"
  >
    <!-- 拖拽上传覆盖遮罩提示 -->
    <div v-if="isDragging && isMultimodal" class="drag-drop-overlay">
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
        <polyline points="17 8 12 3 7 8"></polyline>
        <line x1="12" y1="3" x2="12" y2="15"></line>
      </svg>
      <span>{{ t('chat.dragDropTip') }}</span>
    </div>

    <!-- 待发送附件暂存预览条 -->
    <ChatAttachmentBar
      :staged-files="stagedFiles"
      :is-uploading="isUploading"
      @remove="removeStagedFile"
    />

    <!-- 隐藏的原生文件上传 Input (带文件格式过滤) -->
    <input
      ref="fileInputRef"
      type="file"
      multiple
      accept="image/*,.pdf,.txt,.md,.markdown,.json,.csv,.xml,.yaml,.yml,.log,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.java,.py,.js,.ts,.html,.css,.sql,.sh,.bat,.cmd,.c,.cpp,.h,.hpp,.go,.rs,.kt,.vue"
      style="display: none;"
      @change="handleFileInputChange"
    />

    <!-- Slash 快捷补全下拉（绝对定位于输入区上方） -->
    <ChatSlashDropdown
      v-model:highlight-index="slashHighlightIndex"
      :visible="slashVisible"
      :loading="slashLoading"
      :render-groups="slashRenderGroups"
      @select="handleSlashItemClick"
      @set-item-ref="setSlashItemRef"
    />

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
            :disabled="isConfigSelectDisabled"
            size="small"
            class="provider-select"
            @update:value="handleProviderChange"
          />
          
          <!-- 权限安全级别 -->
          <div class="permission-select-wrapper" style="display: flex; align-items: center; gap: 4px;">
            <n-select
              :value="appStore.permissionMode"
              :options="appStore.permissionModeOptions"
              :disabled="isConfigSelectDisabled"
              @update:value="appStore.handlePermissionModeChange"
              size="small"
              class="permission-select"
            />
            <n-tooltip trigger="hover" placement="top">
              <template #trigger>
                <div class="permission-help-tooltip" style="cursor: help; color: var(--text-color-faint); display: flex; align-items: center;">
                  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                    <line x1="12" y1="17" x2="12.01" y2="17"></line>
                  </svg>
                </div>
              </template>
              <div style="font-size: 0.8rem; line-height: 1.6; padding: 4px; color: var(--text-color-bright); max-width: 260px;">
                <div style="font-weight: bold; border-bottom: 1px solid var(--border-color); margin-bottom: 6px; padding-bottom: 4px;">{{ t('sider.permissionMode') }}:</div>
                <div><strong>{{ t('sider.modeStrictApproval') }}: </strong>{{ t('sider.modeStrictApprovalTooltip') }}</div>
                <div style="margin-top: 4px;"><strong>{{ t('sider.modeRelaxedApproval') }}: </strong>{{ t('sider.modeRelaxedApprovalTooltip') }}</div>
                <div style="margin-top: 4px;"><strong>{{ t('sider.modeAllAllow') }}: </strong>{{ t('sider.modeAllAllowTooltip') }}</div>
              </div>
            </n-tooltip>
          </div>

          <!-- 多模态模型专属：+ 附件上传按钮 -->
          <n-button
            v-if="isMultimodal"
            size="small"
            secondary
            :disabled="isInputDisabled || isUploading"
            :title="t('chat.uploadAttachmentTooltip')"
            @click="triggerSelectFile"
          >
            <template #icon>
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
              </svg>
            </template>
            {{ t('chat.uploadAttachment') }}
          </n-button>
        </div>

        <div class="input-buttons" style="display: flex; gap: 8px;">
          <n-button
            type="primary"
            :class="{ 'send-btn-active': !appStore.loopRunning && (appStore.userInput || stagedFiles.length > 0) && !isSendBlocked }"
            :disabled="!appStore.loopRunning && ((!appStore.userInput && stagedFiles.length === 0) || isSendBlocked || isUploading)"
            @click="appStore.loopRunning ? appStore.cancelLoop() : executeSend()"
          >
            <template v-if="appStore.loopRunning || isUploading" #icon>
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
            {{ appStore.loopRunning ? t('chat.cancelLoop') : isUploading ? t('chat.uploading') : t('chat.send') }}
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
            :disabled="isConfigSelectDisabled"
            size="small"
            style="width: 100%;"
            @update:value="handleProviderChange"
          />
        </div>
        
        <!-- 第二排：换行按钮 + 多模态上传 + 权限模式 (flex: 1 撑满) + 发送按钮 -->
        <div style="display: flex; gap: 8px; align-items: center; width: 100%;">
          <n-button
            secondary
            size="small"
            :disabled="isInputDisabled"
            style="min-width: 60px;"
            @click="insertNewline"
          >
            {{ t('chat.newline') }}
          </n-button>

          <n-button
            v-if="isMultimodal"
            secondary
            size="small"
            :disabled="isInputDisabled || isUploading"
            :title="t('chat.uploadAttachment')"
            @click="triggerSelectFile"
          >
            +
          </n-button>
          
          <div style="flex: 1;">
            <n-select
              :value="appStore.permissionMode"
              :options="appStore.permissionModeOptions"
              :disabled="isConfigSelectDisabled"
              @update:value="appStore.handlePermissionModeChange"
              size="small"
              style="width: 100%;"
            />
          </div>

          <n-button
            type="primary"
            size="small"
            :class="{ 'send-btn-active': !appStore.loopRunning && (appStore.userInput || stagedFiles.length > 0) && !isSendBlocked }"
            :disabled="!appStore.loopRunning && ((!appStore.userInput && stagedFiles.length === 0) || isSendBlocked || isUploading)"
            @click="appStore.loopRunning ? appStore.cancelLoop() : executeSend()"
          >
            <template v-if="appStore.loopRunning || isUploading" #icon>
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
            {{ appStore.loopRunning ? t('chat.cancelLoop') : isUploading ? t('chat.uploading') : t('chat.send') }}
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
import { useConversationStore } from '@/stores/conversation'
import { useProjectStore } from '@/stores/project'
import { useProviderStore } from '@/stores/provider'
import { t } from '@/i18n'
import { useChatAttachments } from './input/composables/useChatAttachments'
import { useSlashCompletion } from './input/composables/useSlashCompletion'
import { useConversationProvider } from './input/composables/useConversationProvider'
import ChatAttachmentBar from './input/components/ChatAttachmentBar.vue'
import ChatSlashDropdown from './input/components/ChatSlashDropdown.vue'

const { isMobile } = useResponsive()
const appStore = useAppStore()
const conversationStore = useConversationStore()
const projectStore = useProjectStore()
const providerStore = useProviderStore()

const inputInstRef = ref<any>(null)
const getTextareaEl = () => inputInstRef.value?.textareaElRef as HTMLTextAreaElement | null | undefined

// 供应商与多模态感知
const {
  activeConversationProviderValue,
  isMultimodal,
  providerOptions,
  handleProviderChange
} = useConversationProvider()

const isInputDisabled = computed(() => {
  return (
    // 环境缺失（无项目 / 无活动项目 / 无会话 / 无供应商）时输入框禁用：
    // 这些前提下后端无从承载本轮对话，输入内容没有落点
    projectStore.projectList.length === 0 ||
    !projectStore.activeProjectId ||
    !conversationStore.activeCid ||
    providerStore.providerList.length === 0
  )
})

// 附件管理链路
const {
  fileInputRef,
  isDragging,
  isUploading,
  stagedFiles,
  triggerSelectFile,
  handleFileInputChange,
  handleDragOver,
  handleDragLeave,
  handleDrop,
  handlePaste,
  removeStagedFile,
  uploadStagedAttachments
} = useChatAttachments({
  isMultimodal,
  isInputDisabled
})

// Slash 快捷补全核心逻辑
const {
  slashVisible,
  slashLoading,
  slashRenderGroups,
  slashHighlightIndex,
  slashDismissed,
  setSlashItemRef,
  handleSlashItemClick,
  closeSlashDropdown,
  handleSlashKeydown
} = useSlashCompletion({
  getTextareaEl
})

// 发送动作的拦截条件：输入框允许在「连接中」与「运行中」先行编辑草稿，
// 但真正触发发送（发送按钮 / Enter 提交）仍须满足：
// ① 已连接服务端——消息体虽走 HTTP，但回显与流式内容全靠 WS 推送，断线期间发送会陷入"发了没反应"的半死状态；
// ② 其它环境缺失项沿用 isInputDisabled 口径
const isSendBlocked = computed(() => !appStore.isConnected || isInputDisabled.value)

// 模型 / 权限配置下拉的统一禁用条件：未连接服务端时不允许选择
// （两者切换都会调用后端接口写回会话配置，断线期间选择只会造成前后端状态不一致，用户却以为已生效），
// 循环执行中同样锁定，避免中途变更影响进行中的回合
const isConfigSelectDisabled = computed(() => !appStore.isConnected || appStore.loopRunning)

const inputPlaceholder = computed(() => {
  // 未连接 WS 时优先提示连接状态：输入框此时仍可编辑草稿，但发送动作被拦截，
  // 需明确告知用户「连接成功后即可发送」，避免误以为消息已发出
  if (!appStore.isConnected) {
    return t('chat.inputPlaceholderDisconnected')
  }
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

// 执行发送前上传流程与发消息联动
const executeSend = async () => {
  if (appStore.loopRunning || isUploading.value) return
  // 发送链路兜底：未连接服务端时静默拦截（按钮与 Enter 均已置灰，此处防程序化调用的漏网场景）
  if (!appStore.isConnected) return
  const text = appStore.userInput.trim()
  if (!text && stagedFiles.value.length === 0) return

  const cid = conversationStore.activeCid
  if (!cid) return

  // 1. 若有待上传附件，先触发批量上传
  if (stagedFiles.value.length > 0) {
    try {
      const attachmentsData = await uploadStagedAttachments(cid)
      // 携带附件数据发送消息
      conversationStore.sendUserMsg(JSON.stringify(attachmentsData))
    } catch {
      // 异常已在 uploadStagedAttachments 内部统一上报与复位，此处终止发消息
    }
  } else {
    // 纯文本消息直接发送
    conversationStore.sendUserMsg()
  }
}

const insertNewline = () => {
  const textarea = getTextareaEl()
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

// 监听活跃会话发生改变，自动聚焦输入框并做草稿/暂存附件交割
watch(
  () => conversationStore.activeCid,
  (newVal, oldVal) => {
    if (newVal) {
      // 草稿与暂存附件交割：旧会话草稿存档、新会话草稿取回输入框；
      // 暂存附件仅隐藏不销毁，File 对象与预览 ObjectURL 均保持有效，切回即原样恢复
      conversationStore.switchDraftContext(oldVal ?? null, newVal)
      // 切换会话时同步关闭 slash 补全下拉；仅当当前输入仍以 / 开头（存在进行中的补全文本）
      // 时才进入豁免期防止继续输入时重开；输入框为空/非 / 开头时不打豁免，
      // 保证切换后首次输入 / 可正常触发（豁免在光标前文本离开 / 形态后自动复位）
      if (appStore.userInput.startsWith('/')) {
        slashDismissed.value = true
      }
      closeSlashDropdown()
      nextTick(() => {
        if (!isInputDisabled.value) {
          inputInstRef.value?.focus()
        }
      })
    }
  }
)

// 监听输入内容被程序化清空（如发送后）：n-input 的 autosize 通过内联 height 撑高，
// 值被程序化清空时 naive-ui 不会重算高度，导致输入框残留旧高度无法回弹，
// 此处手动清掉内联 height，让 autosize 回落到 minRows 高度
watch(
  () => appStore.userInput,
  (newVal, oldVal) => {
    if (!newVal && oldVal) {
      nextTick(() => {
        const textarea = getTextareaEl()
        if (textarea) {
          textarea.style.height = ''
        }
      })
    }
  }
)

const handleEnterKey = (e: KeyboardEvent) => {
  // slash 下拉打开期间，键盘事件优先由补全逻辑接管，绝不走到发送分支
  if (slashVisible.value) {
    handleSlashKeydown(e)
    return
  }

  // 运行中（loopRunning）发送能力已被"取消回合"占用，Enter 不承担发送语义，
  // 原样放行给输入框换行，便于用户提前编辑下一条消息草稿
  if (e.key !== 'Enter' || isSendBlocked.value || appStore.loopRunning) {
    return
  }

  let shouldSend = false
  if (appStore.newlineKey === 'alt+enter') {
    if (e.altKey) {
      shouldSend = true
    }
  } else {
    if (!e.shiftKey && !e.altKey && !e.ctrlKey && !e.metaKey) {
      shouldSend = true
    }
  }

  if (shouldSend) {
    e.preventDefault()
    e.stopPropagation()
    executeSend()
  }
}
</script>

<style scoped>
.chat-footer {
  position: relative;
  transition: border-color 0.2s, background-color 0.2s;
}

.chat-footer.dragging-active {
  border-color: var(--primary-color) !important;
  background-color: var(--primary-bg-weak) !important;
}

.drag-drop-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  background-color: rgba(20, 20, 24, 0.85);
  border: 2px dashed var(--primary-color);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--primary-color);
  font-weight: 500;
  pointer-events: none;
}

.send-btn-active {
  background: linear-gradient(135deg, var(--primary-color) 0%, var(--primary-color-pressed) 100%) !important;
  border: none !important;
  box-shadow: 0 2px 8px var(--border-color-active);
  transition: transform 0.2s, box-shadow 0.2s;
}

.send-btn-active:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px var(--border-color-active);
}

.send-btn-active:active {
  transform: translateY(0);
}

.spin-icon {
  animation: spin 1s linear infinite;
  display: block;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
