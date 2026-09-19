import request from '@/utils/request'

export interface FileUploadVo {
  path: string
  name: string
  size: number
  mimeType: string
  compressed: boolean
}

/**
 * 上传文件到指定会话
 */
export const uploadFile = async (cid: number, file: File, compress = true): Promise<FileUploadVo> => {
  const formData = new FormData()
  formData.append('cid', String(cid))
  formData.append('file', file)
  formData.append('compress', String(compress))

  return request.post('/api/file/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

/**
 * 获取文件在线预览或下载 URL
 * <p>
 * 注意：后端对该接口有沙箱管控，path 必须位于用户目录 ~/.st-cute/files 内，越权一律 404。
 */
export const getFileViewUrl = (path: string, mode: 'raw' | 'thumbnail' = 'raw', download = false): string => {
  if (!path) return ''
  return `/api/file/view?path=${encodeURIComponent(path)}&mode=${mode}&download=${download}`
}
