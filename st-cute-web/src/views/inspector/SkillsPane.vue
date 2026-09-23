<template>
  <div class="pane-content">
    <div style="margin-bottom: 12px;">
      <h4 style="margin: 0; border: none; padding: 0; font-size: 0.85rem; color: var(--text-color-bright); font-weight: bold;">{{ t('inspector.skillsPrompt') }}</h4>
    </div>
    <div v-if="agentStore.skillsList.length === 0" class="empty-state" style="text-align: center; padding: 20px 0;">
      <span>{{ t('inspector.noSkills') }}</span>
    </div>
    <div v-else class="skills-list" style="display: flex; flex-direction: column; gap: 12px;">
      <div
        v-for="skill in agentStore.skillsList"
        :key="skill.name"
        class="skill-card"
        style="background: var(--bg-color); border: 1px solid var(--border-color); border-radius: 6px; padding: 12px;"
      >
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
          <div>
            <strong style="color: var(--text-color-bright); font-size: 0.9rem;">{{ skill.name }}</strong>
          </div>
        </div>
        <div style="font-size: 0.78rem; color: var(--text-color-muted); margin-bottom: 8px;">
          {{ skill.description || t('inspector.none') }}
        </div>
        <div style="font-size: 0.72rem; color: var(--text-color); display: flex; justify-content: space-between; flex-direction: column; gap: 2px;">
          <span>Source: {{ skill.source === 'PROJECT' ? 'Project' : 'Global' }}</span>
          <span
            :title="skill.path"
            style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; cursor: default;"
          >
            <bdi>{{ skill.path }}</bdi>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAgentStore } from '@/stores/agent'
import { t } from '@/i18n'

const agentStore = useAgentStore()
</script>

<style scoped>
.pane-content {
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
  padding: 12px 16px;
}
</style>
