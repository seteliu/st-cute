import request from '@/utils/request'

/**
 * 获取 slash 补全分组列表（当前仅 skill 分组，选项为当前会话可见的全部技能）
 * 前端每次弹出下拉时实时调用，不做缓存
 */
export const getSlashListApi = async (cid: number): Promise<SlashGroupItem[]> => {
  return request.get(`/api/slash/list?cid=${cid}`)
}

/**
 * slash 补全分组结构（与后端 SlashGroupVo 对应）
 */
export interface SlashGroupItem {
  /** 分组名称（如 skill） */
  group: string
  /** 分组下的补全选项 */
  items: SlashItem[]
}

/**
 * slash 补全单选项结构（与后端 SlashItemVo 对应）
 */
export interface SlashItem {
  /** 选项唯一名称（如技能名） */
  name: string
  /** 选项描述说明 */
  description: string
}
