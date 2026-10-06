/**
 * 更新检查结果（GET /api/update/check 响应）
 */
export interface UpdateCheckResult {
  /** 底座类型标识（DESKTOP / SCRIPT / MANUAL） */
  launcherType: 'DESKTOP' | 'SCRIPT' | 'MANUAL'
  /** 底座类型展示名 */
  launcherTypeName: string
  /** 底座自报版本号（MANUAL 或未上报时为 null） */
  launcherVersion: string | null
  /** 当前 jar 版本号 */
  currentVersion: string
  /** 远端最新 jar 版本号（拉取失败时为 null） */
  latestVersion: string | null
  /** 检查结论 */
  status: 'CHECK_FAIL' | 'UNSUPPORTED' | 'OLD_BASE' | 'UPDATABLE' | 'LATEST'
  /** 更新说明 */
  notes: string | null
  /** 旧底座提示文案 */
  oldBaseTip: string | null
  /** 发布页地址 */
  releaseUrl: string | null
  /** 自动升级所需最小底座版本 */
  minBaseVersion: string | null
  /** 上次升级失败原因（24h 内有效，无则 null） */
  lastFailureReason: string | null
}
