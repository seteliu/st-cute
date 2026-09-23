<template>
  <div class="pane-content">
    <div style="margin-bottom: 12px;">
      <h4 style="margin: 0; border: none; padding: 0; font-size: 0.85rem; color: var(--text-color-bright); font-weight: bold;">{{ t('inspector.lifecycleHooks') }}</h4>
    </div>
    <div v-if="agentStore.hooksList.length === 0" class="empty-state" style="text-align: center; padding: 20px 0;">
      <span>{{ t('inspector.noHooks') }}</span>
    </div>
    <div v-else class="hooks-list" style="display: flex; flex-direction: column; gap: 12px;">
      <div
        v-for="hk in agentStore.hooksList"
        :key="hk.name"
        class="hook-card"
        style="background: var(--bg-color); border: 1px solid var(--border-color); border-radius: 6px; padding: 12px;"
      >
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
          <strong style="color: var(--text-color-bright); font-size: 0.9rem;">{{ hk.name }}</strong>
          <span
            :class="['status-tag', hk.blocking ? 'offline' : 'running']"
            :style="hk.blocking
              ? 'font-size: 0.7rem; padding: 1px 5px; border-radius: 3px; background-color: var(--status-error-bg); color: var(--status-error); border: 1px solid var(--status-error);'
              : 'font-size: 0.7rem; padding: 1px 5px; border-radius: 3px; background-color: var(--status-success-bg); color: var(--success-green); border: 1px solid var(--success-green);'"
          >
            {{ hk.blocking ? t('inspector.blocking') : t('inspector.nonBlocking') }}
          </span>
        </div>
        <div style="font-size: 0.78rem; color: var(--text-color-muted); margin-bottom: 6px;">
          {{ t('inspector.eventAspect') }} <code style="color: var(--accent-color); font-family: monospace;">{{ hk.event }}</code>
        </div>
        <div
          style="font-size: 0.75rem; color: var(--text-color); font-family: monospace; word-break: break-all; background: var(--bg-color-card); padding: 6px; border-radius: 4px; border: 1px solid var(--border-color);"
        >
          {{ t('inspector.command') }} {{ hk.args?.command || t('inspector.none') }}
        </div>
        <div style="font-size: 0.72rem; color: var(--text-color); margin-top: 6px; display: flex; gap: 12px;">
          <span v-if="hk.toolFilter">{{ t('inspector.filterTool') }} {{ hk.toolFilter }}</span>
          <span v-if="hk.pattern">{{ t('inspector.globMatch') }} {{ hk.pattern }}</span>
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
