<template>
  <div
    class="pane-content review-pane-v2"
    style="display: flex; flex-direction: column; height: 100%; gap: 12px; padding: 12px 16px;"
  >
    <!-- 1. 加载中状态 -->
    <div
      v-if="worktreeStore.loadingWorktrees && worktreeStore.activeWorktrees.length === 0"
      style="display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; text-align: center; color: #888;"
    >
      <n-spin size="medium" :description="t('common.loading')" />
    </div>

    <!-- 2. 没有活跃的工作区列表 -->
    <div
      v-else-if="worktreeStore.activeWorktrees.length === 0"
      class="empty-state"
      style="display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; text-align: center; color: #888;"
    >
      <span style="font-size: 1.0rem; font-weight: bold; margin-bottom: 6px; color: #ccc;">{{ t('inspector.noWorktrees') }}</span>
      <span style="font-size: 0.85rem; max-width: 280px; line-height: 1.5; color: #666;"
        >{{ t('review.noChanges') }}</span
      >
    </div>

    <!-- 3. 正常渲染 -->
    <template v-else>
      <!-- 头部工具栏 -->
      <div
        class="worktree-toolbar"
        style="display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #2d2d30; padding-bottom: 12px;"
      >
        <n-select
          :value="worktreeStore.selectedWorktree?.branch"
          :options="worktreeStore.activeWorktrees.map(w => ({ label: `${t('review.branch')} ${w.branch}`, value: w.branch }))"
          @update:value="worktreeStore.onSelectWorktreeBranch"
          style="flex: 1;"
        />
        <n-button size="tiny" type="primary" secondary style="margin-left: 8px;" @click="worktreeStore.fetchWorktrees">
          {{ t('review.refresh') }}
        </n-button>
      </div>

      <!-- 变动文件列表区 (单栏宽敞展示) -->
      <div
        class="worktree-content"
        style="display: flex; flex: 1; min-height: 0; flex-direction: column; gap: 8px; height: calc(100vh - 240px);"
      >
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
          <span style="font-size: 0.8rem; font-weight: bold; color: #888;">
            {{ t('review.modifiedFiles') }} ({{ worktreeStore.worktreeDiffs.length }})
          </span>
          <span style="font-size: 0.75rem; color: #666;">{{ t('review.diffTip') }}</span>
        </div>

        <div
          v-if="worktreeStore.loadingDiff"
          style="display: flex; justify-content: center; align-items: center; padding: 24px 0;"
        >
          <n-spin size="small" :description="t('common.loading')" />
        </div>

        <template v-else>
          <div
            v-if="worktreeStore.worktreeDiffs.length === 0"
            style="color: #666; font-size: 0.85rem; padding: 12px 0; text-align: center;"
          >
            {{ t('review.noChanges') }}
          </div>
          <div
            v-else
            class="file-list"
            style="flex: 1; display: flex; flex-direction: column; gap: 4px; overflow-y: auto;"
          >
            <div
              v-for="fd in worktreeStore.worktreeDiffs"
              :key="fd.filename"
              class="file-item"
              @click="handleFileClick(fd)"
              style="padding: 8px 12px; border-radius: 6px; cursor: pointer; font-size: 0.85rem; word-break: break-all; transition: all 0.2s; display: flex; align-items: center; justify-content: space-between; gap: 8px;"
            >
              <div style="display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1;">
                <span :class="['change-dot', fd.changeType?.toLowerCase() || 'modify']"></span>
                <span class="file-name-text" style="text-overflow: ellipsis; white-space: nowrap; overflow: hidden;">{{ fd.filename }}</span>
              </div>
              
              <span :class="['change-badge', fd.changeType?.toLowerCase() || 'modify']">
                {{ getChangeBadgeText(fd.changeType) }}
              </span>
            </div>
          </div>
        </template>
      </div>
    </template>

    <!-- 弹窗预览 Diff -->
    <n-modal
      v-model:show="showDiffModal"
      preset="card"
      style="width: 85vw; max-width: 1400px; height: 80vh;"
      :title="t('review.viewDiffTitle')"
      bordered
    >
      <div style="display: flex; flex-direction: column; height: calc(80vh - 120px); gap: 12px;">
        <div style="font-size: 0.95rem; font-weight: bold; word-break: break-all; color: var(--text-color-bright);">
          File: <span style="color: var(--primary-color);">{{ worktreeStore.selectedFileDiff?.filename }}</span>
        </div>
        <div style="flex: 1; min-height: 0; overflow: auto; background: #0c0c0e; border-radius: 6px; border: 1px solid var(--border-color);">
          <div v-if="showDiffModal && worktreeStore.selectedFileDiff" class="diff-table">
            <div
              v-for="(line, idx) in formattedDiff"
              :key="idx"
              :class="['diff-row', line.type]"
            >
              <div class="line-num left-num">{{ line.leftNum }}</div>
              <div class="line-num right-num">{{ line.rightNum }}</div>
              <div class="line-sign">{{ line.sign }}</div>
              <div class="line-content">{{ line.text }}</div>
            </div>
          </div>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useWorktreeStore } from '@/stores/worktree'
import { t } from '@/i18n'

const worktreeStore = useWorktreeStore()
const showDiffModal = ref(false)

const handleFileClick = (fd: any) => {
  worktreeStore.selectFileDiff(fd)
  showDiffModal.value = true
}

onMounted(() => {
  worktreeStore.fetchWorktrees()
})

const getChangeBadgeText = (type?: string) => {
  if (type === 'ADD') return 'N'
  if (type === 'DELETE') return 'D'
  return 'M'
}

const formattedDiff = computed(() => {
  const diff = worktreeStore.selectedFileDiff?.diffContent
  if (!diff) return []
  
  const lines = diff.split('\n')
  let leftLineNum = 0
  let rightLineNum = 0
  
  return lines.map(line => {
    let type = 'normal'
    let sign = ' '
    let leftNumStr = ''
    let rightNumStr = ''
    
    if (line.startsWith('+') && !line.startsWith('+++')) {
      type = 'add'
      sign = '+'
      rightNumStr = String(++rightLineNum)
    } else if (line.startsWith('-') && !line.startsWith('---')) {
      type = 'delete'
      sign = '-'
      leftNumStr = String(++leftLineNum)
    } else if (line.startsWith('@@')) {
      type = 'info'
      sign = ''
      const match = line.match(/^@@\s+-(\d+),?\d*\s+\+(\d+),?\d*\s+@@/)
      if (match) {
        leftLineNum = parseInt(match[1], 10) - 1
        rightLineNum = parseInt(match[2], 10) - 1
      }
    } else if (line.startsWith('diff') || line.startsWith('index') || line.startsWith('---') || line.startsWith('+++')) {
      type = 'meta'
      sign = ''
    } else {
      type = 'normal'
      sign = ' '
      leftNumStr = String(++leftLineNum)
      rightNumStr = String(++rightLineNum)
    }
    
    let displayText = line;
    if (type === 'add' || type === 'delete') {
      displayText = line.substring(1);
    }
    
    return {
      text: displayText,
      type,
      sign,
      leftNum: leftNumStr,
      rightNum: rightNumStr
    }
  })
})
</script>

<style scoped>
.file-item {
  background-color: transparent;
  color: var(--text-color);
  border: 1px solid transparent;
}

.file-item:hover {
  background-color: rgba(255, 255, 255, 0.05);
  color: var(--text-color-bright);
}

.file-item.active {
  background-color: var(--bg-color-card-active) !important;
  color: var(--primary-color) !important;
  border: 1px solid var(--border-color-active) !important;
}

.change-dot {
  width: 6px;
  height: 6px;
  min-width: 6px;
  border-radius: 50%;
}
.change-dot.add {
  background-color: #38b078;
}
.change-dot.modify {
  background-color: var(--primary-color);
}
.change-dot.delete {
  background-color: var(--status-error);
}

.file-item.active .change-dot {
  background-color: var(--primary-color) !important;
}

.change-badge {
  font-size: 0.78rem;
  font-weight: bold;
  user-select: none;
  padding-right: 4px;
}
.change-badge.add {
  color: #38b078;
}
.change-badge.modify {
  color: var(--primary-color);
}
.change-badge.delete {
  color: var(--status-error);
}

.diff-table {
  display: flex;
  flex-direction: column;
  font-family: Consolas, Monaco, 'Andale Mono', monospace;
  font-size: 0.85rem;
  background-color: #0c0c0e;
  overflow: hidden;
  user-select: text;
}

.diff-row {
  display: flex;
  line-height: 1.6;
  min-height: 22px;
}

.line-num {
  width: 45px;
  min-width: 45px;
  text-align: right;
  padding-right: 10px;
  color: var(--text-color-muted);
  background-color: var(--bg-color-card);
  user-select: none;
  font-size: 0.78rem;
  border-right: 1px solid var(--border-color);
}

.line-sign {
  width: 25px;
  min-width: 25px;
  text-align: center;
  user-select: none;
  font-weight: bold;
}

.line-content {
  flex: 1;
  white-space: pre-wrap;
  word-break: break-all;
  padding-left: 8px;
}

.diff-row.add {
  background-color: rgba(46, 160, 67, 0.15) !important;
}
.diff-row.add .line-content {
  color: var(--text-color-bright);
}
.diff-row.add .line-sign {
  color: #3fb950;
}
.diff-row.add .line-num {
  color: #3fb950;
}

.diff-row.delete {
  background-color: rgba(248, 81, 73, 0.15) !important;
}
.diff-row.delete .line-content {
  color: var(--text-color-muted);
  text-decoration: line-through rgba(248, 81, 73, 0.4);
}
.diff-row.delete .line-sign {
  color: #f85149;
}
.diff-row.delete .line-num {
  color: #f85149;
}

.diff-row.info {
  background-color: rgba(187, 187, 233, 0.05) !important;
}
.diff-row.info .line-content {
  font-weight: 500;
  color: var(--purple-color);
}
.diff-row.info .line-num {
  background-color: rgba(187, 187, 233, 0.03);
  color: var(--text-color-secondary);
}

.diff-row.meta {
  background-color: var(--bg-color-card-active);
  color: var(--text-color-secondary);
  border-bottom: 1px dashed var(--border-color);
}
.diff-row.meta .line-content {
  font-weight: bold;
}

.diff-row.normal {
  color: var(--text-color);
}
.diff-row.normal:hover {
  background-color: rgba(255, 255, 255, 0.015);
}
</style>
