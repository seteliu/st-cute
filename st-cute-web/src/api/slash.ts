import request from '@/utils/request'
import { SlashGroupItem } from '@/types'

/**
 * 获取 slash 补全分组列表（当前仅 skill 分组，选项为当前会话可见的全部技能）
 * 前端每次弹出下拉时实时调用，不做缓存
 */
export const getSlashListApi = async (cid: number): Promise<SlashGroupItem[]> => {
  return request.get(`/api/slash/list?cid=${cid}`)
}
