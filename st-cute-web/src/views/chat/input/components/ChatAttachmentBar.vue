<template>
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
        :title="t('chat.removeAttachment')"
        @click.stop="emit('remove', item.id)"
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
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="var(--success-green)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12"></polyline>
        </svg>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { StagedFile } from '@/types'
import { formatFileSize } from '../composables/useChatAttachments'
import { t } from '@/i18n'

defineProps<{
  stagedFiles: StagedFile[]
  isUploading: boolean
}>()

const emit = defineEmits<{
  (e: 'remove', id: string): void
}>()
</script>

<style scoped>
.staged-attachments-bar {
  display: flex;
  flex-wrap: nowrap;
  overflow-x: auto;
  overflow-y: hidden;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--overlay-veil);
  background-color: var(--overlay-veil);
  border-top-left-radius: 8px;
  border-top-right-radius: 8px;
  scrollbar-width: thin;
}

.staged-attachments-bar::-webkit-scrollbar {
  height: 4px;
}

.staged-attachments-bar::-webkit-scrollbar-thumb {
  background-color: var(--scrollbar-thumb);
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
  background-color: var(--overlay-veil);
  border: 1px solid var(--overlay-veil-strong);
  border-radius: 6px;
  padding: 4px 8px;
  max-width: 220px;
  overflow: hidden;
  transition: opacity 0.3s ease, border-color 0.3s;
}

.staged-attachment-card.card-success {
  border-color: var(--success-green);
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
  background-color: var(--primary-bg-weak);
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
  color: var(--text-color-bright);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-filesize {
  font-size: 0.68rem;
  color: var(--text-color-faint);
}

.card-remove-btn {
  background: none;
  border: none;
  color: var(--text-color-muted);
  cursor: pointer;
  padding: 2px 4px;
  font-size: 0.75rem;
  border-radius: 4px;
  transition: color 0.2s, background-color 0.2s;
}

.card-remove-btn:hover {
  color: var(--status-error);
  background-color: var(--status-error-bg);
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
</style>
