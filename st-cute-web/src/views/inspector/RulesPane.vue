<template>
  <div class="pane-content">
    <div style="margin-bottom: 12px;">
      <h4 style="margin: 0; border: none; padding: 0; font-size: 0.85rem; color: #a0a0a5;">{{ t('inspector.customRules') }}</h4>
    </div>

    <!-- 空白状态 -->
    <div v-if="agentStore.rulesList.length === 0" class="empty-state" style="text-align: center; padding: 20px 0;">
      <span>{{ t('inspector.noRules') }}</span>
    </div>

    <!-- 规约列表 -->
    <div v-else class="rules-list" style="display: flex; flex-direction: column; gap: 12px;">
      <div
        v-for="rule in agentStore.rulesList"
        :key="rule.path"
        class="rule-card"
        @click="showDetail(rule)"
      >
        <div class="rule-header">
          <strong class="rule-name">{{ rule.name }}</strong>
          <n-button size="tiny" type="primary" secondary class="view-btn">
            {{ t('chat.detail') }}
          </n-button>
        </div>
        
        <div class="rule-details">
          <div class="detail-item">
            <span class="label">{{ t('inspector.workspacePath') }}</span>
            <span class="val path-text" :title="rule.path">{{ rule.path }}</span>
          </div>
          <div class="detail-item">
            <span class="label">Time:</span>
            <span class="val">{{ rule.updateTime }}</span>
          </div>
          <div class="detail-item">
            <span class="label">Size:</span>
            <span class="val">{{ formatSize(rule.size) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 详情模态框 -->
    <n-modal
      v-model:show="showModal"
      preset="card"
      style="width: 90%; max-width: 800px; background: #18181c; border: 1px solid #2d2d30;"
      :title="selectedRule?.name + ' - AGENTS.md ' + t('chat.detail')"
      bordered
    >
      <n-scrollbar style="max-height: 60vh;" trigger="none">
        <div style="padding: 4px 16px 16px 4px;">
          <div class="msg-content markdown-body" v-html="renderedMarkdown"></div>
        </div>
      </n-scrollbar>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { marked } from 'marked'
import { AgentRule } from '@/types'
import { t } from '@/i18n'

const agentStore = useAgentStore()
const showModal = ref(false)
const selectedRule = ref<AgentRule | null>(null)

const showDetail = (rule: AgentRule) => {
  selectedRule.value = rule
  showModal.value = true
}

const renderedMarkdown = computed(() => {
  if (!selectedRule.value || !selectedRule.value.content) return t('inspector.noData')
  try {
    return marked.parse(selectedRule.value.content, { async: false, gfm: true, breaks: true }) as string
  } catch (e) {
    console.error('Markdown 渲染失败:', e)
    return selectedRule.value.content
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
  }
})

const formatSize = (bytes: number) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}
</script>

<style scoped>
.rule-card {
  background: #101014;
  border: 1px solid #2d2d30;
  border-radius: 6px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.25, 0.8, 0.25, 1);
}

.rule-card:hover {
  border-color: var(--primary-color);
  background: #141418;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.rule-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.rule-name {
  color: #ffffff;
  font-size: 0.9rem;
}

.view-btn {
  pointer-events: none;
}

.rule-card:hover .view-btn {
  background-color: rgba(129, 182, 229, 0.16) !important;
  color: #81b6e5 !important;
}

.rule-details {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.detail-item {
  display: flex;
  font-size: 0.76rem;
  line-height: 1.35;
}

.detail-item .label {
  color: #707075;
  width: 65px;
  flex-shrink: 0;
}

.detail-item .val {
  color: #c2c2c9;
  word-break: break-all;
}

.path-text {
  font-family: monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  direction: rtl;
  text-align: left;
}
</style>
