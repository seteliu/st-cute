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
    <div v-if="stagedFiles.length > 0" class="staged-attachments-bar">
      <div
        v-for="item in stagedFiles"
        :key="item.id"
        class="staged-attachment-card"
        :class="{ 'card-uploading': item.status === 'uploading', 'card-success': item.status === 'success' }"
      >
        <!-- 图片缩略图预览 (支持点击放大查看) -->
        <div v-if="item.isImage && item.previewUrl" class="card-thumb-wrapper">
          <n-image
            :src="item.previewUrl"
            :preview-src="item.previewUrl"
            class="card-thumb"
            object-fit="cover"
            show-toolbar-tooltip
          />
        </div>
        <!-- 普通文件图标 -->
        <div v-else class="card-icon-wrapper">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path>
            <polyline points="13 2 13 9 20 9"></polyline>
          </svg>
        </div>

        <!-- 文件信息 -->
        <div class="card-info">
          <span class="card-filename" :title="item.name">{{ item.name }}</span>
          <span class="card-filesize">{{ formatFileSize(item.size) }}</span>
        </div>

        <!-- 移除按钮 (非上传中状态展示) -->
        <button
          v-if="item.status !== 'uploading' && !isUploading"
          class="card-remove-btn"
          @click.stop="removeStagedFile(item.id)"
          title="移除附件"
        >
          ✕
        </button>

        <!-- 上传中 Loading 遮罩转圈 -->
        <div v-if="item.status === 'uploading'" class="card-status-overlay uploading-overlay">
          <svg class="spin-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <line x1="12" y1="2" x2="12" y2="6"></line>
            <line x1="12" y1="18" x2="12" y2="22"></line>
            <line x1="4.93" y1="4.93" x2="7.76" y2="7.76"></line>
            <line x1="16.24" y1="16.24" x2="19.07" y2="19.07"></line>
            <line x1="2" y1="12" x2="6" y2="12"></line>
            <line x1="18" y1="12" x2="22" y2="12"></line>
            <line x1="4.93" y1="19.07" x2="7.76" y2="16.24"></line>
            <line x1="16.24" y1="7.76" x2="19.07" y2="4.93"></line>
          </svg>
        </div>

        <!-- 上传完成打勾遮罩 (降低透明度) -->
        <div v-if="item.status === 'success'" class="card-status-overlay success-overlay">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#63e2b7" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="20 6 9 17 4 12"></polyline>
          </svg>
        </div>
      </div>
    </div>

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
    <div v-if="slashVisible" ref="slashDropdownRef" class="slash-dropdown">
      <div v-if="slashLoading" class="slash-hint">加载中...</div>
      <div v-else-if="slashRenderGroups.length === 0" class="slash-hint">无匹配项</div>
      <div v-else>
        <div v-for="group in slashRenderGroups" :key="group.group" class="slash-group">
          <div class="slash-group-title">{{ group.group }}</div>
          <div
            v-for="(item, idx) in group.items"
            :key="item.name"
            :ref="el => setSlashItemRef(el, group.startFlatIndex + idx)"
            class="slash-item"
            :class="{ 'slash-item-active': group.startFlatIndex + idx === slashHighlightIndex }"
            @mousedown.prevent
            @click="handleSlashItemClick(item)"
            @mousemove="slashHighlightIndex = group.startFlatIndex + idx"
          >
            <div class="slash-item-name">/{{ item.name }}</div>
            <div v-if="item.description" class="slash-item-desc" :title="item.description">{{ item.description }}</div>
          </div>
        </div>
      </div>
    </div>

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

          <!-- 多模态模型专属：+ 附件上传按钮 -->
          <n-button
            v-if="isMultimodal"
            size="small"
            secondary
            :disabled="isInputDisabled || isUploading"
            @click="triggerSelectFile"
            title="上传图片/文件附件 (最多5个，单个<=10MB)"
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
            {{ appStore.loopRunning ? t('chat.cancelLoop') : isUploading ? '上传中...' : t('chat.send') }}
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
            @click="triggerSelectFile"
            title="上传附件"
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
            {{ appStore.loopRunning ? t('chat.cancelLoop') : isUploading ? '上传中...' : t('chat.send') }}
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
import { updateConversationProviderApi } from '@/api/conversation'
import { getSlashListApi } from '@/api/slash'
import type { SlashGroupItem, SlashItem } from '@/api/slash'
import { uploadFile } from '@/api/file'
import type { StagedFile } from '@/types'
import { t } from '@/i18n'

const { isMobile } = useResponsive()
const appStore = useAppStore()
const conversationStore = useConversationStore()
const projectStore = useProjectStore()
const providerStore = useProviderStore()

const inputInstRef = ref<any>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

// 暂存文件结构 StagedFile 已上移至 src/types/chat.ts（供 store 与视图共用）

// 暂存附件按会话吸附：读侧直接映射 store 中当前会话的暂存列表，
// 切换会话自动隐藏、切回自动恢复；写侧统一经 ensureStagedList 取真实数组操作，
// 避免 computed 求值期间产生副作用。无附件时不预建条目，统一复用空数组常量
const EMPTY_STAGED_LIST: StagedFile[] = []
const stagedFiles = computed<StagedFile[]>(() => {
  const cid = conversationStore.activeCid
  if (cid === null) return EMPTY_STAGED_LIST
  return conversationStore.stagedFilesMap[cid] || EMPTY_STAGED_LIST
})

// 获取当前会话的暂存列表（不存在则初始化空数组），仅写入路径使用；无活跃会话时返回 null
const ensureStagedList = (): StagedFile[] | null => {
  const cid = conversationStore.activeCid
  if (cid === null) return null
  let list = conversationStore.stagedFilesMap[cid]
  if (!list) {
    list = []
    conversationStore.stagedFilesMap[cid] = list
  }
  return list
}
const isDragging = ref(false)
const isUploading = ref(false)

// ==================== Slash 快捷补全状态 ====================
// 下拉是否弹出（光标前文本构成 /+关键词 形态时为 true）
const slashVisible = ref(false)
// 后端返回的原始分组数据（每次弹出实时拉取，不缓存）
const slashGroups = ref<SlashGroupItem[]>([])
// 当前过滤关键词（光标前文本中 / 与光标之间的输入片段）
const slashKeyword = ref('')
// 过滤后拍平的选项总数中当前高亮的索引（默认高亮第一项）
const slashHighlightIndex = ref(0)
// 请求中标记
const slashLoading = ref(false)
// 关闭豁免标记：选中回填或手动关闭后，光标前文本不再重新构成 / 形态前不再自动弹出，
// 避免回填赋值触发 watch 导致「关闭又立即重开」的抖动；
// 光标前文本离开 / 形态后自动复位，之后重新输入 / 可再次触发
const slashDismissed = ref(false)
// 下拉容器 DOM 引用（用于键盘导航时滚动跟随）
const slashDropdownRef = ref<HTMLElement | null>(null)
// 全部选项元素引用表：拍平索引 -> DOM 元素
const slashItemEls = new Map<number, HTMLElement>()

/**
 * 收集下拉选项元素引用（v-for 动态 ref 回调）
 */
const setSlashItemRef = (el: any, flatIndex: number) => {
  if (el) {
    slashItemEls.set(flatIndex, el as HTMLElement)
  }
}

/**
 * 键盘移动高亮后，将当前高亮项滚动到下拉可视范围内（不居中，仅贴边最小滚动）
 */
const scrollHighlightedIntoView = () => {
  nextTick(() => {
    const el = slashItemEls.get(slashHighlightIndex.value)
    el?.scrollIntoView({ block: 'nearest' })
  })
}

const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i]
}

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

const triggerSelectFile = () => {
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
    fileInputRef.value.click()
  }
}

const handleFileInputChange = (e: Event) => {
  const target = e.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    addFilesToStaging(Array.from(target.files))
  }
}

const handleDragOver = (e: DragEvent) => {
  if (!isMultimodal.value) return
  isDragging.value = true
}

const handleDragLeave = (e: DragEvent) => {
  isDragging.value = false
}

const handleDrop = (e: DragEvent) => {
  isDragging.value = false
  if (!isMultimodal.value) return
  if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
    addFilesToStaging(Array.from(e.dataTransfer.files))
  }
}

const handlePaste = (e: ClipboardEvent) => {
  if (!isMultimodal.value || isInputDisabled.value) return
  const clipboardData = e.clipboardData
  if (!clipboardData) return

  const items = clipboardData.items
  const pastedFiles: File[] = []

  if (items && items.length > 0) {
    for (let i = 0; i < items.length; i++) {
      const item = items[i]
      if (item.kind === 'file') {
        const file = item.getAsFile()
        if (file) {
          let fileName = file.name
          if (!fileName || fileName === 'image.png' || fileName === 'blob') {
            const ext = file.type.split('/')[1] || 'png'
            const timestamp = new Date().toISOString().replace(/[-:T.Z]/g, '').slice(0, 14)
            fileName = `paste_${timestamp}.${ext}`
          }
          const namedFile = new File([file], fileName, { type: file.type })
          pastedFiles.push(namedFile)
        }
      }
    }
  } else if (clipboardData.files && clipboardData.files.length > 0) {
    for (let i = 0; i < clipboardData.files.length; i++) {
      pastedFiles.push(clipboardData.files[i])
    }
  }

  if (pastedFiles.length > 0) {
    e.preventDefault()
    addFilesToStaging(pastedFiles)
  }
}

const ALLOWED_EXTS = new Set([
  'jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'svg',
  'pdf', 'txt', 'md', 'markdown', 'json', 'csv', 'xml', 'yaml', 'yml', 'log',
  'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'java', 'py', 'js', 'ts', 'html', 'css', 'sql', 'sh', 'bat', 'cmd',
  'c', 'cpp', 'h', 'hpp', 'go', 'rs', 'kt', 'vue'
])

const addFilesToStaging = (files: File[]) => {
  if (stagedFiles.value.length + files.length > 5) {
    const msg = t('chat.attachmentLimitTip') || '最多上传 5 个附件'
    if ((window as any).$message) {
      ;(window as any).$message.warning(msg)
    }
    return
  }

  const list = ensureStagedList()
  if (!list) return

  for (const f of files) {
    if (f.size > 10 * 1024 * 1024) {
      const msg = `${f.name}: ${t('chat.attachmentSizeLimit') || '单文件大小不能超过 10MB'}`
      if ((window as any).$message) {
        ;(window as any).$message.warning(msg)
      }
      continue
    }

    const ext = f.name.includes('.') ? f.name.split('.').pop()?.toLowerCase() || '' : ''
    const isImg = f.type.startsWith('image/') || ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'svg'].includes(ext)
    const isAllowed = isImg || (ext && ALLOWED_EXTS.has(ext))

    if (!isAllowed) {
      const msg = `${f.name || '附件'}: ${t('chat.attachmentFormatError') || '不支持的文件格式'}`
      if ((window as any).$message) {
        ;(window as any).$message.warning(msg)
      }
      continue
    }

    const previewUrl = isImg ? URL.createObjectURL(f) : undefined

    list.push({
      id: 'staged_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7),
      file: f,
      name: f.name,
      size: f.size,
      isImage: isImg,
      previewUrl,
      status: 'idle'
    })
  }
}

const removeStagedFile = (id: string) => {
  const list = ensureStagedList()
  if (!list) return
  const idx = list.findIndex(item => item.id === id)
  if (idx >= 0) {
    const item = list[idx]
    if (item.previewUrl) {
      URL.revokeObjectURL(item.previewUrl)
    }
    list.splice(idx, 1)
  }
}

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
    isUploading.value = true
    try {
      const uploadTasks = stagedFiles.value.map(async (item) => {
        if (item.status !== 'success' || !item.uploadedPath) {
          item.status = 'uploading'
          try {
            const res = await uploadFile(cid, item.file, true)
            // 更新实际存储物理大小与 MIME 类型
            if (res.size != null) {
              item.size = res.size
            }
            // 校验最终存储大小不超过 1024KB (1MB)
            if (res.size && res.size > 1024 * 1024) {
              item.status = 'idle'
              const sizeStr = formatFileSize(res.size)
              throw new Error(`附件【${item.name}】处理后大小为 ${sizeStr}，超过了 1024KB 上限，已被拦截无法发送`)
            }
            item.status = 'success'
            item.uploadedPath = res.path
            item.mimeType = res.mimeType
          } catch (err) {
            item.status = 'idle'
            throw err
          }
        }
        return item
      })

      await Promise.all(uploadTasks)

      // 短暂延时 300ms 呈现半透明遮罩与打勾动画
      await new Promise(resolve => setTimeout(resolve, 300))

      const attachmentsData = stagedFiles.value.map(item => ({
        path: item.uploadedPath,
        name: item.name,
        size: item.size,
        mimeType: item.mimeType
      }))

      // 此处不再清空暂存附件：统一改由 store 在发送成功后 dispose 释放，
      // 发送失败时保留暂存（含已上传成功的 uploadedPath），用户重试不丢附件
      isUploading.value = false

      // 携带附件数据发送消息
      conversationStore.sendUserMsg(JSON.stringify(attachmentsData))

    } catch (e: any) {
      isUploading.value = false
      // 将所有由于中断或异常仍处于 uploading 状态的附件复位为 idle，避免持续转圈卡死
      stagedFiles.value.forEach(item => {
        if (item.status === 'uploading') {
          item.status = 'idle'
        }
      })
      console.error('上传附件处理失败:', e)
      const errMsg = e.response?.data?.msg || e.message || '上传附件失败，请重试'
      if ((window as any).$message) {
        ;(window as any).$message.error(errMsg)
      }
    }
  } else {
    // 纯文本消息直接发送
    conversationStore.sendUserMsg()
  }
}

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
        const textarea = inputInstRef.value?.textareaElRef
        if (textarea) {
          textarea.style.height = ''
        }
      })
    }
  }
)

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

// ==================== Slash 快捷补全核心逻辑 ====================

/**
 * 将过滤后的分组数据渲染为带拍平索引的结构，供高亮与键盘选中定位使用
 */
const slashRenderGroups = computed(() => {
  const keyword = slashKeyword.value.trim().toLowerCase()
  let flatIndex = 0
  return slashGroups.value
    .map(group => {
      const filteredItems = (group.items || []).filter(item =>
        !keyword || item.name.toLowerCase().includes(keyword)
      )
      const startFlatIndex = flatIndex
      flatIndex += filteredItems.length
      return { group: group.group, items: filteredItems, startFlatIndex }
    })
    .filter(group => group.items.length > 0)
})

/**
 * 过滤后的选项总条数（空列表时 Enter 完全无效的判定依据）
 */
const slashFilteredCount = computed(() => {
  return slashRenderGroups.value.reduce((sum, group) => sum + group.items.length, 0)
})

/**
 * 关闭下拉并复位状态
 */
const closeSlashDropdown = () => {
  slashVisible.value = false
  slashGroups.value = []
  slashKeyword.value = ''
  slashHighlightIndex.value = 0
  slashLoading.value = false
  // 清空选项元素引用表，避免下次弹出残留旧 DOM 引用
  slashItemEls.clear()
}

/**
 * 选中补全项：基于光标区间的局部替换回填（不整体覆盖输入，
 * 保留 / 之前与关键词之后的其他正文内容），关闭下拉，光标移至
 * 插入内容末尾并保持聚焦
 */
const applySlashItem = (item: SlashItem) => {
  const textarea = inputInstRef.value?.textareaElRef
  const text = appStore.userInput || ''
  // 与触发 watch 同源的定位规则：以当前光标位置为终点，取光标前文本做 /关键词 正则匹配，
  // 命中则只替换「/ 起到光标为止」的片段；不依赖 slashKeyword 状态，
  // 避免下拉打开期间 ←/→ 移动光标（不触发 watch）导致的状态与光标漂移
  if (textarea) {
    const end = textarea.selectionStart ?? text.length
    const m = text.slice(0, end).match(/^\/([a-zA-Z0-9_-]*)$/)
    if (m) {
      const start = end - m[1].length
      // 先打上豁免标记再回填：回填赋值会触发输入 watch，
      // 若不打标记会因光标前文本仍为 / 形态而被误判为重新触发（关闭后立即重开的抖动）
      slashDismissed.value = true
      // 仅替换光标前的 /关键词 片段，保留 / 之前与光标之后的其他正文
      appStore.userInput = text.slice(0, start - 1) + `/${item.name} ` + text.slice(end)
      nextTick(() => {
        // 光标落在插入内容末尾（/{name}+空格 之后），保持聚焦
        const cursor = start - 1 + item.name.length + 2
        textarea.selectionStart = textarea.selectionEnd = cursor
        textarea.focus()
      })
      closeSlashDropdown()
      return
    }
  }
  // 兜底：无法定位光标或光标前不构成 / 形态时退回整体回填（正常场景不会走到这里）
  // 先打上豁免标记再回填，理由同上
  slashDismissed.value = true
  appStore.userInput = `/${item.name} `
  closeSlashDropdown()
  nextTick(() => {
    const ta = inputInstRef.value?.textareaElRef
    if (ta) {
      ta.selectionStart = ta.selectionEnd = (appStore.userInput || '').length
      ta.focus()
    }
  })
}

const handleSlashItemClick = (item: SlashItem) => {
  applySlashItem(item)
}

/**
 * 拉取 slash 分组列表（每次弹出实时请求，不缓存）
 */
const fetchSlashList = async () => {
  const cid = conversationStore.activeCid
  if (!cid) return
  slashLoading.value = true
  try {
    const res = await getSlashListApi(cid)
    slashGroups.value = Array.isArray(res) ? res : []
  } catch (e) {
    console.error('拉取 slash 补全列表失败:', e)
    slashGroups.value = []
  } finally {
    slashLoading.value = false
  }
}

/**
 * 监听输入变化，判定 slash 触发与关闭（光标感知版）
 * 触发条件：光标前文本恰好为「/ + 纯关键词」（/^\/[a-zA-Z0-9_-]*$/），
 * 即 / 与光标之间只有关键词、无空格无其他正文——
 * 这样空框输入 /、在已有内容首部插入 /（光标停在 / 与正文之间）均能正常触发，
 * 且关键词只取 / 后到光标的片段，不会带着后面正文去匹配；
 * 而 问题/xxx、行中斜杠等场景因 / 前有其他字符则不再误弹
 */
watch(
  () => appStore.userInput,
  (newVal) => {
    const textarea = inputInstRef.value?.textareaElRef
    // 取光标位置：取不到时退回文本末尾（与旧版整体匹配行为对齐）
    const cursorPos = textarea ? (textarea.selectionStart ?? (newVal || '').length) : (newVal || '').length
    const textBeforeCursor = (newVal || '').slice(0, cursorPos)
    // 光标前文本须严格匹配 /关键词 形态，/ 前不能再有其他字符
    const match = textBeforeCursor.match(/^\/([a-zA-Z0-9_-]*)$/)

    if (!match) {
      // 光标前文本不构成 / 触发形态（含清空、光标已越过斜杠区）时关闭下拉，并复位豁免标记，
      // 使之后重新输入 / 能再次正常触发
      if (slashVisible.value) {
        closeSlashDropdown()
      }
      slashDismissed.value = false
      return
    }

    // 提取光标前 / 与光标之间的关键词用于过滤
    slashKeyword.value = match[1]

    // 处于豁免期（选中回填/手动关闭后同一文本生命周期）时不自动弹出
    if (slashDismissed.value) {
      return
    }

    if (!slashVisible.value) {
      // 未连接服务端时不弹出补全下拉：选项需从后端实时拉取，断线期间请求必然失败并弹错误提示；
      // 连接恢复后用户重新输入 / 可正常触发
      if (!appStore.isConnected) return
      // 触发瞬间：弹出下拉并实时拉取后端列表
      slashVisible.value = true
      slashHighlightIndex.value = 0
      fetchSlashList()
    }
  }
)

/**
 * slash 下拉打开期间的键盘接管处理
 * 设计约束：接管期间绝不触发消息发送；带修饰键的 Enter 原样放行换行；IME 组词期放行
 */
const handleSlashKeydown = (e: KeyboardEvent) => {
  // 中文输入法组词期间的 Enter 为「确认候选词上屏」，不接管，原样放行
  if (e.isComposing || e.keyCode === 229) {
    return
  }

  // 高亮索引归一化：数据变化（过滤结果减少）后索引可能越界，钳制到有效范围
  if (slashHighlightIndex.value >= slashFilteredCount.value) {
    slashHighlightIndex.value = Math.max(0, slashFilteredCount.value - 1)
  }

  switch (e.key) {
    case 'ArrowDown':
      if (slashFilteredCount.value > 0) {
        e.preventDefault()
        slashHighlightIndex.value = (slashHighlightIndex.value + 1) % slashFilteredCount.value
        scrollHighlightedIntoView()
      }
      break
    case 'ArrowUp':
      if (slashFilteredCount.value > 0) {
        e.preventDefault()
        slashHighlightIndex.value = (slashHighlightIndex.value - 1 + slashFilteredCount.value) % slashFilteredCount.value
        scrollHighlightedIntoView()
      }
      break
    case 'Escape':
      e.preventDefault()
      // 手动关闭同样进入豁免期：同一 / 开头文本生命周期内不再自动弹出，
      // 输入不再以 / 开头（删掉斜杠/清空）后自动复位
      slashDismissed.value = true
      closeSlashDropdown()
      break
    case 'Enter':
      // 仅裸 Enter 用于选中补全；带修饰键的 Enter 原样放行给输入框换行
      if (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) {
        return
      }
      e.preventDefault()
      e.stopPropagation()
      if (slashFilteredCount.value > 0) {
        const item = resolveHighlightedItem()
        if (item) {
          applySlashItem(item)
        }
      }
      // 列表为空时：什么都不做（既不选中也不发送）
      break
    default:
      break
  }
}

/**
 * 根据当前高亮索引在渲染分组中定位对应选项
 */
const resolveHighlightedItem = (): SlashItem | null => {
  for (const group of slashRenderGroups.value) {
    const offset = slashHighlightIndex.value - group.startFlatIndex
    if (offset >= 0 && offset < group.items.length) {
      return group.items[offset]
    }
  }
  return null
}

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
.chat-footer {
  position: relative;
  transition: border-color 0.2s, background-color 0.2s;
}

.chat-footer.dragging-active {
  border-color: var(--primary-color) !important;
  background-color: rgba(129, 182, 229, 0.05) !important;
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

.staged-attachments-bar {
  display: flex;
  flex-wrap: nowrap;
  overflow-x: auto;
  overflow-y: hidden;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  background-color: rgba(255, 255, 255, 0.02);
  border-top-left-radius: 8px;
  border-top-right-radius: 8px;
  scrollbar-width: thin;
}

.staged-attachments-bar::-webkit-scrollbar {
  height: 4px;
}

.staged-attachments-bar::-webkit-scrollbar-thumb {
  background-color: rgba(255, 255, 255, 0.18);
  border-radius: 2px;
}

.staged-attachments-bar::-webkit-scrollbar-track {
  background-color: transparent;
}

.staged-attachment-card {
  position: relative;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  background-color: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  padding: 4px 8px;
  max-width: 220px;
  overflow: hidden;
  transition: opacity 0.3s ease, border-color 0.3s;
}

.staged-attachment-card.card-success {
  border-color: rgba(99, 226, 183, 0.4);
}

.card-thumb-wrapper {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  overflow: hidden;
  flex-shrink: 0;
  cursor: pointer;
}

:deep(.card-thumb) {
  width: 28px;
  height: 28px;
  display: block;
}

:deep(.card-thumb img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--primary-color);
  background-color: rgba(129, 182, 229, 0.1);
  border-radius: 4px;
  flex-shrink: 0;
}

.card-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex: 1;
}

.card-filename {
  font-size: 0.75rem;
  color: #e3e3e7;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-filesize {
  font-size: 0.68rem;
  color: #8c8c93;
}

.card-remove-btn {
  background: none;
  border: none;
  color: #a0a0a5;
  cursor: pointer;
  padding: 2px 4px;
  font-size: 0.75rem;
  border-radius: 4px;
  transition: color 0.2s, background-color 0.2s;
}

.card-remove-btn:hover {
  color: #ff7875;
  background-color: rgba(255, 120, 117, 0.1);
}

.card-status-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.uploading-overlay {
  background-color: rgba(0, 0, 0, 0.65);
  color: var(--primary-color);
}

.success-overlay {
  background-color: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(1px);
}

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

/* ==================== Slash 快捷补全下拉样式 ==================== */
.slash-dropdown {
  position: absolute;
  /* 弹出在输入区上方，与 .chat-footer 顶部保持 4px 间距 */
  bottom: calc(100% + 4px);
  left: 8px;
  right: 8px;
  max-height: 280px;
  overflow-y: auto;
  background-color: rgba(30, 30, 34, 0.98);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.45);
  z-index: 15;
  scrollbar-width: thin;
}

.slash-dropdown::-webkit-scrollbar {
  width: 4px;
}

.slash-dropdown::-webkit-scrollbar-thumb {
  background-color: rgba(255, 255, 255, 0.18);
  border-radius: 2px;
}

.slash-dropdown::-webkit-scrollbar-track {
  background-color: transparent;
}

.slash-hint {
  padding: 10px 12px;
  font-size: 0.78rem;
  color: #8c8c93;
  text-align: center;
}

.slash-group + .slash-group {
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.slash-group-title {
  padding: 6px 12px 4px;
  font-size: 0.68rem;
  font-weight: 600;
  color: #a0a0a5;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.slash-item {
  padding: 6px 12px;
  cursor: pointer;
  transition: background-color 0.15s;
}

/* 高亮统一由 slash-item-active 类呈现（键盘导航与鼠标悬浮共用同一来源，
   避免与 :hover 伪类叠加出现双高亮），鼠标悬浮经 mousemove 设置高亮索引 */
.slash-item-active {
  background-color: rgba(129, 182, 229, 0.15);
}

.slash-item-name {
  font-size: 0.8rem;
  color: #e3e3e7;
  font-family: monospace;
}

.slash-item-desc {
  margin-top: 2px;
  font-size: 0.7rem;
  color: #8c8c93;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

</style>
