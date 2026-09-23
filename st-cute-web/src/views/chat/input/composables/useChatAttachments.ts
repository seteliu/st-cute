import { computed, ref, type Ref } from 'vue'
import { useConversationStore } from '@/stores/conversation'
import { uploadFile } from '@/api/file'
import type { StagedFile } from '@/types'
import { t } from '@/i18n'

const ALLOWED_EXTS = new Set([
  'jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'svg',
  'pdf', 'txt', 'md', 'markdown', 'json', 'csv', 'xml', 'yaml', 'yml', 'log',
  'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'java', 'py', 'js', 'ts', 'html', 'css', 'sql', 'sh', 'bat', 'cmd',
  'c', 'cpp', 'h', 'hpp', 'go', 'rs', 'kt', 'vue'
])

export const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i]
}

export function useChatAttachments(options: {
  isMultimodal: Ref<boolean>
  isInputDisabled: Ref<boolean>
}) {
  const conversationStore = useConversationStore()
  const fileInputRef = ref<HTMLInputElement | null>(null)
  const isDragging = ref(false)
  const isUploading = ref(false)

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
    if (!options.isMultimodal.value) return
    isDragging.value = true
  }

  const handleDragLeave = (e: DragEvent) => {
    isDragging.value = false
  }

  const handleDrop = (e: DragEvent) => {
    isDragging.value = false
    if (!options.isMultimodal.value) return
    if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      addFilesToStaging(Array.from(e.dataTransfer.files))
    }
  }

  const handlePaste = (e: ClipboardEvent) => {
    if (!options.isMultimodal.value || options.isInputDisabled.value) return
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

  /**
   * 执行待发送附件的批量物理上传，返回上传成功的附件元数据数组
   */
  const uploadStagedAttachments = async (cid: number) => {
    if (stagedFiles.value.length === 0) return []
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
              throw new Error(t('chat.attachmentProcessedSizeLimit', { name: item.name, size: sizeStr }))
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
        path: item.uploadedPath || '',
        name: item.name,
        size: item.size,
        mimeType: item.mimeType
      }))

      isUploading.value = false
      return attachmentsData
    } catch (e: any) {
      isUploading.value = false
      // 将所有由于中断或异常仍处于 uploading 状态的附件复位为 idle，避免持续转圈卡死
      stagedFiles.value.forEach(item => {
        if (item.status === 'uploading') {
          item.status = 'idle'
        }
      })
      console.error('上传附件处理失败:', e)
      const errMsg = e.response?.data?.msg || e.message || t('chat.uploadFailed')
      if ((window as any).$message) {
        ;(window as any).$message.error(errMsg)
      }
      throw e
    }
  }

  return {
    fileInputRef,
    isDragging,
    isUploading,
    stagedFiles,
    formatFileSize,
    triggerSelectFile,
    handleFileInputChange,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    handlePaste,
    addFilesToStaging,
    removeStagedFile,
    uploadStagedAttachments
  }
}
