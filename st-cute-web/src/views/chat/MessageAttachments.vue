<template>
  <div v-if="parsedAttachments.length > 0" class="message-attachments-container">
    <div
      v-for="att in parsedAttachments"
      :key="att.path"
      class="attachment-item-wrapper"
      @contextmenu.prevent="handleContextMenu($event, att)"
    >
      <!-- 图片类型展示 -->
      <div v-if="isImageFile(att)" class="attachment-image-card">
        <n-image
          :src="getThumbUrl(att.path)"
          :preview-src="getRawUrl(att.path)"
          object-fit="cover"
          class="attachment-img-preview"
          :alt="att.name"
          show-toolbar-tooltip
        />
        <div class="attachment-img-meta" :title="att.name">
          <span class="attachment-name">{{ att.name }}</span>
        </div>
      </div>

      <!-- 普通文件类型卡片展示 -->
      <div v-else class="attachment-file-card" @click="handleDownload(att)">
        <div class="file-icon-box">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path>
            <polyline points="13 2 13 9 20 9"></polyline>
          </svg>
        </div>
        <div class="file-info-box">
          <span class="file-name" :title="att.name">{{ att.name }}</span>
          <span class="file-size">{{ formatFileSize(att.size) }}</span>
        </div>
        <div class="file-action-icon" title="点击下载">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
            <polyline points="7 10 12 15 17 10"></polyline>
            <line x1="12" y1="15" x2="12" y2="3"></line>
          </svg>
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <n-dropdown
      placement="bottom-start"
      trigger="manual"
      :x="contextMenuX"
      :y="contextMenuY"
      :options="dropdownOptions"
      :show="showContextMenu"
      :on-clickoutside="() => showContextMenu = false"
      @select="handleDropdownSelect"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { getFileViewUrl } from '@/api/file'
import { t } from '@/i18n'

interface AttachmentItem {
  path: string
  name: string
  size?: number
  mimeType?: string
}

const props = defineProps<{
  attachments?: string | AttachmentItem[]
}>()

const parsedAttachments = computed<AttachmentItem[]>(() => {
  if (!props.attachments) return []
  if (Array.isArray(props.attachments)) return props.attachments
  try {
    const arr = JSON.parse(props.attachments)
    return Array.isArray(arr) ? arr : []
  } catch (e) {
    return []
  }
})

const isImageFile = (att: AttachmentItem): boolean => {
  if (att.mimeType && att.mimeType.startsWith('image/')) return true
  const ext = att.name ? att.name.split('.').pop()?.toLowerCase() || '' : ''
  return ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'svg'].includes(ext)
}

const getThumbUrl = (path: string): string => {
  return getFileViewUrl(path, 'thumbnail')
}

const getRawUrl = (path: string): string => {
  return getFileViewUrl(path, 'raw')
}

const formatFileSize = (bytes?: number): string => {
  if (!bytes || bytes === 0) return ''
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i]
}

// 右键菜单交互
const showContextMenu = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const currentTargetAttachment = ref<AttachmentItem | null>(null)

const dropdownOptions = computed(() => [
  {
    label: t('chat.downloadFile') || '另存为 / 下载',
    key: 'download'
  }
])

const handleContextMenu = (e: MouseEvent, att: AttachmentItem) => {
  e.preventDefault()
  e.stopPropagation()
  showContextMenu.value = false
  currentTargetAttachment.value = att
  contextMenuX.value = e.clientX
  contextMenuY.value = e.clientY
  setTimeout(() => {
    showContextMenu.value = true
  }, 50)
}

const handleDropdownSelect = (key: string) => {
  showContextMenu.value = false
  if (key === 'download' && currentTargetAttachment.value) {
    handleDownload(currentTargetAttachment.value)
  }
}

const handleDownload = (att: AttachmentItem) => {
  const downloadUrl = getFileViewUrl(att.path, 'raw', true)
  const a = document.createElement('a')
  a.href = downloadUrl
  a.download = att.name || 'file'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
</script>

<style scoped>
.message-attachments-container {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  margin-top: 4px;
}

.attachment-item-wrapper {
  user-select: none;
}

/* 图片卡片样式 */
.attachment-image-card {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background-color: rgba(0, 0, 0, 0.2);
  width: 130px;
  display: flex;
  flex-direction: column;
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.attachment-image-card:hover {
  transform: translateY(-1px);
  border-color: rgba(129, 182, 229, 0.35);
}

:deep(.attachment-img-preview) {
  width: 130px;
  height: 100px;
  display: block;
}

:deep(.attachment-img-preview img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.attachment-img-meta {
  padding: 4px 6px;
  background-color: rgba(20, 20, 24, 0.75);
  overflow: hidden;
}

.attachment-name {
  font-size: 0.68rem;
  color: #c8c8cf;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: block;
}

/* 普通文件卡片样式 */
.attachment-file-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background-color: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  cursor: pointer;
  max-width: 240px;
  transition: background-color 0.2s ease, border-color 0.2s ease, transform 0.2s ease;
}

.attachment-file-card:hover {
  background-color: rgba(129, 182, 229, 0.08);
  border-color: rgba(129, 182, 229, 0.35);
  transform: translateY(-1px);
}

.file-icon-box {
  color: var(--primary-color);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.file-info-box {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex: 1;
}

.file-name {
  font-size: 0.8rem;
  color: #e3e3e7;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
}

.file-size {
  font-size: 0.7rem;
  color: #8c8c93;
  margin-top: 2px;
}

.file-action-icon {
  color: #8c8c93;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: color 0.2s;
}

.attachment-file-card:hover .file-action-icon {
  color: var(--primary-color);
}
</style>
