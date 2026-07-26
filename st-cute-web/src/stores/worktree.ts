import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getWorktrees, getWorktreeDiff } from '@/api/worktree'
import { Worktree, FileDiff } from '@/types'

import { useConversationStore } from './conversation'

export const useWorktreeStore = defineStore('worktree', () => {
  const activeWorktrees = ref<Worktree[]>([])
  const selectedWorktree = ref<Worktree | null>(null)
  const worktreeDiffs = ref<FileDiff[]>([])
  const selectedFileDiff = ref<FileDiff | null>(null)
  const loadingDiff = ref(false)
  const loadingWorktrees = ref(false)

  // 加载隔离工作树列表
  const fetchWorktrees = async (silent = false) => {
    const conversationStore = useConversationStore()
    const cid = conversationStore.activeCid
    if (cid === null) {
      activeWorktrees.value = []
      selectedWorktree.value = null
      worktreeDiffs.value = []
      selectedFileDiff.value = null
      return
    }
    if (!silent) {
      loadingWorktrees.value = true
    }
    try {
      const data = await getWorktrees(cid)
      activeWorktrees.value = data

      if (activeWorktrees.value && activeWorktrees.value.length > 0) {
        const exists = activeWorktrees.value.find(w => w.branch === selectedWorktree.value?.branch)
        if (!selectedWorktree.value || !exists) {
          selectedWorktree.value = activeWorktrees.value[0]
        } else {
          selectedWorktree.value = exists
        }
        await handleWorktreeChange(silent)
      } else {
        selectedWorktree.value = null
        worktreeDiffs.value = []
        selectedFileDiff.value = null
      }
    } catch (e) {
      console.error('获取 Worktree 列表失败', e)
    } finally {
      if (!silent) {
        loadingWorktrees.value = false
      }
    }
  }

  // 切换选中的工作区副本并拉取文件 diff
  const handleWorktreeChange = async (silent = false) => {
    if (!selectedWorktree.value) return
    const conversationStore = useConversationStore()
    const cid = conversationStore.activeCid
    if (cid === null) return
    if (!silent) {
      loadingDiff.value = true
    }
    try {
      const data = await getWorktreeDiff(selectedWorktree.value.branch, cid)
      const newDiffs = data || []
      
      // 更新变动列表
      worktreeDiffs.value = newDiffs
      
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
      console.error('获取 Worktree diff 失败', e)
    } finally {
      if (!silent) {
        loadingDiff.value = false
      }
    }
  }

  // 选中特定的差异文件
  const selectFileDiff = (fd: FileDiff) => {
    selectedFileDiff.value = fd
  }

  // 手动选择分支
  const onSelectWorktreeBranch = async (branch: string) => {
    const wt = activeWorktrees.value.find(w => w.branch === branch)
    if (wt) {
      selectedWorktree.value = wt
      await handleWorktreeChange()
    }
  }

  return {
    activeWorktrees,
    selectedWorktree,
    worktreeDiffs,
    selectedFileDiff,
    loadingDiff,
    loadingWorktrees,

    fetchWorktrees,
    handleWorktreeChange,
    selectFileDiff,
    onSelectWorktreeBranch
  }
})
