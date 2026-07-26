<template>
  <div class="pane-content">
    <div style="margin-bottom: 12px;">
      <h4 style="margin: 0; border: none; padding: 0; font-size: 0.85rem; color: #a0a0a5;">{{ t('inspector.skillsPrompt') }}</h4>
    </div>
    <div v-if="agentStore.skillsList.length === 0" class="empty-state" style="text-align: center; padding: 20px 0;">
      <span>{{ t('inspector.noSkills') }}</span>
    </div>
    <div v-else class="skills-list" style="display: flex; flex-direction: column; gap: 12px;">
      <div
        v-for="skill in agentStore.skillsList"
        :key="skill.name"
        class="skill-card"
        style="background: #101014; border: 1px solid #2d2d30; border-radius: 6px; padding: 12px;"
      >
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
          <div>
            <strong style="color: #fff; font-size: 0.9rem;">{{ skill.name }}</strong>
          </div>
        </div>
        <div style="font-size: 0.78rem; color: #a0a0a5; margin-bottom: 8px;">
          {{ skill.description || t('inspector.none') }}
        </div>
        <div style="font-size: 0.72rem; color: var(--text-color-secondary); display: flex; justify-content: space-between; flex-direction: column; gap: 2px;">
          <span>Source: {{ skill.source === 'PROJECT' ? 'Project' : 'Global' }}</span>
          <span
            style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left;"
          >
            {{ skill.path }}
          </span>
        </div>
        <div v-if="skill.tools && skill.tools.length > 0" style="margin-top: 8px; border-top: 1px dashed #2d2d30; padding-top: 8px;">
          <div style="font-size: 0.75rem; color: var(--status-warning); font-weight: bold;">Tools:</div>
          <div style="display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px;">
            <span
              v-for="t in skill.tools"
              :key="t"
              style="background: #242428; border: 1px solid #3d3d40; border-radius: 3px; padding: 1px 4px; font-size: 0.7rem; font-family: monospace; color: var(--status-warning);"
            >
              {{ t }}
            </span>
          </div>
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
</style>
