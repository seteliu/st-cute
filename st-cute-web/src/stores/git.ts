import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { getBranches, getBranchDiff } from '@/api/git'
import { GitBranch, FileDiff } from '@/types'

import { useConversationStore } from './conversation'

export const useGitStore = defineStore('git', () => {
  const branches = ref<GitBranch[]>([])
  const selectedBranch = ref<GitBranch | null>(null)
  const branchDiffs = ref<FileDiff[]>([])
  const selectedFileDiff = ref<FileDiff | null>(null)
  const loadingBranches = ref(false)

  // 手动刷新的 loading 最小展示时长（毫秒）：本机请求往往几十毫秒内完成，
  // 转圈一闪而过毫无感知，保底展示让用户确认「刷新已生效」
  const MIN_LOADING_MS = 400
  const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

  // 加载分支列表
  const fetchBranches = async (silent = false) => {
    const conversationStore = useConversationStore()
    const cid = conversationStore.activeCid
    if (cid === null) {
      branches.value = []
      selectedBranch.value = null
      branchDiffs.value = []
      selectedFileDiff.value = null
      return
    }
    const start = Date.now()
    if (!silent) {
      loadingBranches.value = true
    }
    try {
      const data = await getBranches(cid, silent)
      branches.value = data

      if (branches.value && branches.value.length > 0) {
        // 下拉仅作分支清单展示（不可切换），选中项始终跟随仓库当前检出分支（current 标记）：
        // 用户在终端切分支后，轮询刷新会让下拉自动跟切；无标记（异常防御）时回退第一个
        const currentBranch = branches.value.find(w => w.current)
        selectedBranch.value = currentBranch || branches.value[0]
        await handleBranchChange(silent)
      } else {
        selectedBranch.value = null
        branchDiffs.value = []
        selectedFileDiff.value = null
      }
    } catch (e) {
      console.error('获取分支列表失败', e)
    } finally {
      if (!silent) {
        // 补足最小 loading 时长：请求过快时等待至保底时长再收起，保证转圈可感知
        const elapsed = Date.now() - start
        if (elapsed < MIN_LOADING_MS) {
          await sleep(MIN_LOADING_MS - elapsed)
        }
        loadingBranches.value = false
      }
    }
  }

  // 切换选中的分支并拉取文件 diff（列表就地静默换新，不引入额外转圈；手动反馈由刷新按钮的 loading 承载）
  const handleBranchChange = async (silent = false) => {
    if (!selectedBranch.value) return
    const conversationStore = useConversationStore()
    const cid = conversationStore.activeCid
    if (cid === null) return
    try {
      const data = await getBranchDiff(selectedBranch.value.branch, cid, silent)
      const newDiffs = data || []

      // 更新变动列表
      branchDiffs.value = newDiffs

      if (newDiffs.length > 0) {
        // 如果之前已经有选中的文件，且该文件依然在新列表中存在，则继续保持选中它，避免被强行重置为第一个
        const currentSelected = selectedFileDiff.value
        const stillExists = currentSelected ? newDiffs.find(fd => fd.filename === currentSelected.filename) : null

        if (stillExists) {
          selectedFileDiff.value = stillExists
        } else {
          selectedFileDiff.value = newDiffs[0]
        }
      } else {
        selectedFileDiff.value = null
      }
    } catch (e) {
      console.error('获取分支 diff 失败', e)
    }
  }

  // 选中特定的差异文件
  const selectFileDiff = (fd: FileDiff) => {
    selectedFileDiff.value = fd
  }

  // 分支下拉仅作清单展示（当前分支高亮、其余置灰），暂不提供切换，无 onSelectBranch 入口

  return {
    branches,
    selectedBranch,
    branchDiffs,
    selectedFileDiff,
    loadingBranches,

    fetchBranches,
    selectFileDiff
  }
})

// 启用 Pinia store 热更新：dev 热替换时复用原 store 实例，避免新旧实例并存导致组件状态分裂、刷新链断裂
if (import.meta.hot) {
  acceptHMRUpdate(useGitStore, import.meta.hot)
}
