export interface Project {
  id: number
  name: string
  path: string
  createTime?: string
  updateTime?: string
  expanded?: boolean
  active?: boolean
}

/** 文件上传结果（与后端 FileUploadVo 对齐） */
export interface FileUploadVo {
  path: string
  name: string
  size: number
  mimeType: string
  compressed: boolean
}
