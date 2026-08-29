import request from '@/utils/request'

export interface FileUploadVo {
  path: string
  name: string
  size: number
  mimeType: string
  compressed: boolean
}

export interface FileBase64Vo {
  mimeType: string
  base64: string
  size: number
  name?: string
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
 */
export const getFileViewUrl = (path: string, mode: 'raw' | 'thumbnail' = 'raw', download = false): string => {
  if (!path) return ''
  return `/api/file/view?path=${encodeURIComponent(path)}&mode=${mode}&download=${download}`
}

/**
 * 获取指定文件的 Base64 数据
 */
export const getFileBase64 = async (path: string): Promise<FileBase64Vo> => {
  return request.get(`/api/file/base64?path=${encodeURIComponent(path)}`)
}
