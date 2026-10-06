import request from '@/utils/request'
import { UpdateCheckResult } from '@/types'

/**
 * 更新检查：返回当前运行方式、底座/jar 版本与检查结论（五态）。
 * <p>
 * refresh=true 时绕过服务端 5 分钟缓存强制回源，供「重新检查」入口使用。
 * </p>
 */
export const checkUpdateApi = async (refresh = false): Promise<UpdateCheckResult> => {
  // silent：检查失败（网络异常/元数据非法）不弹全局错误提示，由关于页区块内失败态渲染
  return request.get('/api/update/check', { params: { refresh }, silent: true })
}

/**
 * 查询后端升级会话状态：true 表示有升级流程进行中（下载/校验阶段）。
 * 用于刷新页面或新开标签页后恢复进度展示。
 */
export const getUpdateStatusApi = async (): Promise<boolean> => {
  return request.get('/api/update/status', { silent: true })
}

/**
 * 执行升级：后端下载校验新包后将以退出码 88 重启，进度经 WS 事件推送。
 * <p>
 * 受理即视为升级流程开始（服务将重启）；前置失败（不支持/并发占用/元数据异常）
 * 以业务错误抛出由调用方展示。
 * </p>
 */
export const applyUpdateApi = async (): Promise<string> => {
  return request.post('/api/update/apply')
}
