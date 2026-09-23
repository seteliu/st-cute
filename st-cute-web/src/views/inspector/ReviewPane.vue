<template>
  <div
    class="pane-content review-pane-v2"
    style="display: flex; flex-direction: column; height: 100%; gap: 12px; padding: 12px 16px; box-sizing: border-box; overflow: hidden;"
  >
    <!-- 1. 加载中状态 -->
    <div
      v-if="gitStore.loadingBranches && gitStore.branches.length === 0"
      style="display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; text-align: center; color: var(--text-color-muted);"
    >
      <n-spin size="medium" :description="t('common.loading')" />
    </div>

    <!-- 2. 没有活跃的工作区列表 -->
    <div
      v-else-if="gitStore.branches.length === 0"
      class="empty-state"
      style="display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; text-align: center; color: var(--text-color-muted);"
    >
      <span style="font-size: 1.0rem; font-weight: bold; margin-bottom: 6px; color: var(--text-color-bright);">      {{ t('inspector.noBranches') }}</span>
      <span style="font-size: 0.85rem; max-width: 280px; line-height: 1.5; color: var(--text-color-muted);"
        >{{ t('review.noChanges') }}</span
      >
    </div>

    <!-- 3. 正常渲染 -->
    <template v-else>
      <!-- 头部工具栏 -->
      <div
        class="branch-toolbar"
        style="display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border-color); padding-bottom: 12px; flex-shrink: 0;"
      >
        <n-select
          :value="gitStore.selectedBranch?.branch"
          :options="gitStore.branches.map(w => ({
            label: `${t('review.branch')} ${w.branch}`,
            value: w.branch,
            disabled: !w.current,
            // 当前分支高亮、其余置灰：下拉仅作分支清单展示，暂不提供切换
            class: w.current ? 'branch-option-current' : 'branch-option-muted'
          }))"
          :consistent-menu-width="false"
          style="flex: 1;"
        />
        <n-button size="tiny" type="primary" secondary style="margin-left: 8px;" :loading="gitStore.loadingBranches" @click="gitStore.fetchBranches()">
          {{ t('review.refresh') }}
        </n-button>
      </div>

      <!-- 变动文件列表区 (单栏宽敞展示) -->
      <div
        class="branch-content"
        style="display: flex; flex: 1; min-height: 0; flex-direction: column; gap: 8px; overflow: hidden;"
      >
        <div style="display: flex; align-items: center; margin-bottom: 4px; flex-shrink: 0;">
          <span style="font-size: 0.8rem; font-weight: bold; color: var(--text-color-muted);">
            {{ t('review.modifiedFiles') }} ({{ gitStore.branchDiffs.length }})
          </span>
        </div>

        <div
          v-if="gitStore.branchDiffs.length === 0"
          style="color: var(--text-color-muted); font-size: 0.85rem; padding: 12px 0; text-align: center;"
        >
          {{ t('review.noChanges') }}
        </div>
        <div
          v-else
          class="file-list"
          style="flex: 1; display: flex; flex-direction: column; gap: 4px; overflow-y: auto;"
        >
            <div
              v-for="fd in gitStore.branchDiffs"
              :key="fd.filename"
              class="file-item"
              @click="handleFileClick(fd)"
              style="padding: 8px 12px; border-radius: 6px; cursor: pointer; font-size: 0.85rem; word-break: break-all; transition: all 0.2s; display: flex; align-items: center; justify-content: space-between; gap: 8px;"
            >
              <div style="display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1;">
                <span :class="['change-dot', fd.changeType?.toLowerCase() || 'modify']"></span>
                <span
                  class="file-name-text"
                  :title="fd.filename"
                  style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; min-width: 0; flex: 1;"
                >
                  <bdi>{{ fd.filename }}</bdi>
                </span>
              </div>
              
              <span :class="['change-badge', fd.changeType?.toLowerCase() || 'modify']">
                {{ getChangeBadgeText(fd.changeType) }}
              </span>
            </div>
          </div>
      </div>
    </template>

    <!-- 弹窗预览 Diff -->
    <n-modal
      v-model:show="showDiffModal"
      preset="card"
      style="width: 85vw; max-width: 1400px; height: 80vh; border: 1px solid var(--border-color); box-shadow: var(--shadow-overlay);"
      :title="t('review.viewDiffTitle')"
    >
      <div style="display: flex; flex-direction: column; height: calc(80vh - 120px); gap: 12px;">
        <div style="font-size: 0.95rem; font-weight: bold; word-break: break-all; color: var(--text-color-bright);">
          File: <span style="color: var(--accent-color);">{{ gitStore.selectedFileDiff?.filename }}</span>
        </div>
        <div style="flex: 1; min-height: 0; overflow: auto; background: var(--bg-color-code); border-radius: 6px; border: 1px solid var(--border-color);">
          <div v-if="showDiffModal && gitStore.selectedFileDiff" class="diff-table">
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
import { ref, computed } from 'vue'
import { useGitStore } from '@/stores/git'
import { t } from '@/i18n'

const gitStore = useGitStore()
const showDiffModal = ref(false)

const handleFileClick = (fd: any) => {
  gitStore.selectFileDiff(fd)
  showDiffModal.value = true
}

// 进入面板不主动拉取：分支与变动列表由 Home 的常驻轮询静默维护，store 中始终有较新数据，
// 此处立即刷新会让已有列表凭空转圈重载；仅保留工具栏「刷新」按钮作为用户手动强刷入口

const getChangeBadgeText = (type?: string) => {
  if (type === 'ADD') return 'N'
  if (type === 'DELETE') return 'D'
  return 'M'
}

const formattedDiff = computed(() => {
  const diff = gitStore.selectedFileDiff?.diffContent
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
  background-color: var(--overlay-veil);
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
  background-color: var(--status-success);
}
.change-dot.modify {
  background-color: var(--accent-color);
}
.change-dot.delete {
  background-color: var(--status-error);
}

.file-item.active .change-dot.modify {
  background-color: var(--accent-color) !important;
}

.change-badge {
  font-size: 0.78rem;
  font-weight: bold;
  user-select: none;
  padding-right: 4px;
}
.change-badge.add {
  color: var(--status-success);
}
.change-badge.modify {
  color: var(--accent-color);
}
.change-badge.delete {
  color: var(--status-error);
}

.diff-table {
  display: flex;
  flex-direction: column;
  font-family: Consolas, Monaco, 'Andale Mono', monospace;
  font-size: 0.85rem;
  background-color: var(--bg-color-code);
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
  background-color: var(--diff-add-bg) !important;
}
.diff-row.add .line-content {
  color: var(--text-color-bright);
}
.diff-row.add .line-sign {
  color: var(--status-success);
}
.diff-row.add .line-num {
  color: var(--status-success);
}

.diff-row.delete {
  background-color: var(--diff-remove-bg) !important;
}
.diff-row.delete .line-content {
  color: var(--text-color-muted);
  text-decoration: line-through rgba(208, 48, 80, 0.4);
}
.diff-row.delete .line-sign {
  color: var(--status-error);
}
.diff-row.delete .line-num {
  color: var(--status-error);
}

.diff-row.info {
  background-color: var(--overlay-veil) !important;
}
.diff-row.info .line-content {
  font-weight: 500;
  color: var(--accent-color);
}
.diff-row.info .line-num {
  background-color: var(--overlay-veil);
  color: var(--text-color-muted);
}

.diff-row.meta {
  background-color: var(--accent-bg-weak);
  color: var(--accent-color);
  border-bottom: 1px dashed rgba(187, 187, 233, 0.25);
}
.diff-row.meta .line-content {
  font-weight: bold;
  color: var(--accent-color);
}
.diff-row.meta .line-num {
  background-color: var(--accent-bg-weak);
}

.diff-row.normal {
  color: var(--text-color);
}
.diff-row.normal:hover {
  background-color: var(--overlay-veil);
}

/* 分支下拉清单：当前分支高亮、其余置灰（仅展示不可切换） */
:deep(.branch-option-current.n-base-select-option .n-base-select-option__content) {
  color: var(--primary-color);
  font-weight: bold;
}
:deep(.branch-option-muted.n-base-select-option .n-base-select-option__content) {
  color: var(--text-color-muted);
}
:deep(.branch-option-muted.n-base-select-option.n-base-select-option--disabled .n-base-select-option__content) {
  color: var(--text-color-faint);
}
</style>
