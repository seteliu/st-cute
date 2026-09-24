<template>
  <!-- 系统设置大弹窗 -->
  <n-modal
    :show="show"
    :auto-focus="false"
    preset="card"
    style="width: 760px; max-width: 95vw; min-height: 480px; border: 1px solid var(--border-color); box-shadow: var(--shadow-overlay);"
    :title="isAddingOrEditingProvider ? (providerStore.isEditing ? t('sider.editProvider') : (providerStore.copySourceModelName ? t('sider.copyProviderTitle') : t('sider.addProvider'))) : t('settings.title')"
    size="medium"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <!-- 供应商专属子页面 -->
    <div v-if="isAddingOrEditingProvider" class="provider-sub-view">
      <n-form :label-placement="isMobile ? 'top' : 'left'" label-width="135" style="margin-top: 15px;">
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.protocol') }}
          </template>
          <n-select v-model:value="providerStore.form.protocol" :options="providerStore.protocolOptions" />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.groupName') }} (Group)
          </template>
          <n-input
            v-model:value="providerStore.form.group"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.groupPlaceholder')"
            spellcheck="false"
            @keydown.enter.prevent
            maxlength="50"
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.modelName') }}
          </template>
          <n-input
            ref="modelNameInputRef"
            v-model:value="providerStore.form.modelName"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.modelNamePlaceholder')"
            spellcheck="false"
            @keydown.enter.prevent
            maxlength="100"
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.baseUrl') }}
          </template>
          <div style="display: flex; flex-direction: column; width: 100%; gap: 6px;">
            <n-input
              v-model:value="providerStore.form.baseUrl"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 1 }"
              :placeholder="t('sider.baseUrlPlaceholder')"
              spellcheck="false"
              @keydown.enter.prevent
              maxlength="255"
            />
            <n-checkbox v-model:checked="providerStore.form.useFullUrl">
              {{ t('sider.useFullUrl') }}
            </n-checkbox>
          </div>
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.apiKey') }}
          </template>
          <n-input
            v-model:value="providerStore.form.apiKey"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.apiKeyPlaceholder')"
            spellcheck="false"
            @keydown.enter.prevent
            maxlength="255"
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px;">*</span>{{ t('sider.contextSize') }}
          </template>
          <n-input-number v-model:value="providerStore.form.contextSize" :min="50000" :max="10000000" :step="1000" :placeholder="t('sider.contextSizePlaceholder')" style="width: 100%;" :input-props="{ spellcheck: 'false' }" />
        </n-form-item>
        <n-form-item>
          <template #label>
            <SettingLabelTip :label="t('sider.multimodal')" :tip="t('sider.multimodalTooltip')" />
          </template>
          <n-switch v-model:value="providerStore.form.multimodal" />
        </n-form-item>
        <n-form-item :label="t('sider.maxTokens')">
          <n-input-number
            v-model:value="providerStore.form.maxTokens"
            :min="1"
            :max="128000"
            :placeholder="t('sider.maxTokensPlaceholder')"
            :clearable="true"
            style="width: 100%;"
          />
        </n-form-item>
        <n-form-item :label="t('sider.reasoningEffort')">
          <n-input
            v-model:value="providerStore.form.reasoningEffort"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.reasoningEffortPlaceholder')"
            spellcheck="false"
            @keydown.enter.prevent
            maxlength="50"
            :clearable="true"
          />
        </n-form-item>
        <n-form-item :label="t('sider.temperature')">
          <n-input-number
            v-model:value="providerStore.form.temperature"
            :min="0"
            :max="2"
            :step="0.1"
            :placeholder="t('sider.temperaturePlaceholder')"
            :clearable="true"
            style="width: 100%;"
          />
        </n-form-item>
      </n-form>
      <div style="display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px;">
        <n-button type="primary" @click="saveProviderAndReturn">{{ t('common.save') }}</n-button>
        <n-button @click="cancelProviderEdit">{{ t('common.back') }}</n-button>
      </div>
    </div>

    <!-- 设置主界面（Tab卡片） -->
    <div v-else class="settings-main-view">
      <n-tabs v-model:value="activeTab" placement="top" animated>
        <!-- 卡片1：基础设置 -->
        <n-tab-pane name="basic" :tab="t('settings.basicTab')">
          <div class="settings-pane-content">
            <!-- 语言设置（放在最前面） -->
            <div class="setting-item-row setting-item-row--stack">
              <div class="setting-item-label">
                <SettingLabelTip :label="t('settings.language')" :tip="t('settings.languageTooltip')" />
              </div>
              <n-select
                v-model:value="appStore.language"
                :options="[
                  { label: t('settings.langZh'), value: 'zh-CN' },
                  { label: t('settings.langEn'), value: 'en-US' }
                ]"
                class="setting-item-control select-control"
                @update:value="onLanguageChange"
              />
            </div>

            <!-- 主题设置（放在语言设置下面） -->
            <div class="setting-item-row setting-item-row--stack">
              <div class="setting-item-label">
                <SettingLabelTip :label="t('settings.theme')" :tip="t('settings.themeTooltip')" />
              </div>
              <n-select
                v-model:value="appStore.theme"
                :options="[
                  { label: t('settings.themeDark'), value: 'dark' },
                  { label: t('settings.themeLight'), value: 'light' }
                ]"
                class="setting-item-control select-control"
                @update:value="onThemeChange"
              />
            </div>

            <div class="setting-item-row setting-item-row--stack">
              <div class="setting-item-label">
                <SettingLabelTip :label="t('settings.newlineKey')" :tip="t('settings.newlineKeyTooltip')" />
              </div>
              <n-select
                v-model:value="appStore.newlineKey"
                :options="[
                  { label: t('settings.enterKey'), value: 'enter' },
                  { label: t('settings.altEnterKey'), value: 'alt+enter' }
                ]"
                class="setting-item-control select-control"
                @update:value="appStore.saveBasicConfig"
              />
            </div>

            <div class="setting-item-row">
              <div class="setting-item-label">
                <SettingLabelTip :label="t('settings.pathSandbox')" :tip="t('settings.pathSandboxTooltip')" />
              </div>
              <n-switch
                v-model:value="appStore.pathSandboxEnabled"
                @update:value="appStore.saveBasicConfig"
              />
            </div>

            <!-- 原始 HTTP 日志组：父项为大选项标准行（右侧总开关），开启后子项经树形连接线缩进挂接 -->
            <div class="setting-group">
              <div class="setting-item-row setting-group-parent">
                <div class="setting-item-label">
                  <SettingLabelTip :label="t('settings.httpLog')" :tip="t('settings.httpLogTooltip')" />
                </div>
                <n-switch
                  v-model:value="appStore.httpLog"
                  @update:value="appStore.saveBasicConfig"
                />
              </div>
              <div v-if="appStore.httpLog" class="setting-tree-children">
                <div class="setting-item-row setting-tree-child">
                  <div class="setting-item-label">
                    <SettingLabelTip :label="t('settings.httpLogIncludeResponse')" :tip="t('settings.httpLogIncludeResponseTooltip')" />
                  </div>
                  <n-switch
                    v-model:value="appStore.httpLogIncludeResponse"
                    @update:value="appStore.saveBasicConfig"
                  />
                </div>
                <div class="setting-item-row setting-tree-child">
                  <div class="setting-item-label">
                    <SettingLabelTip :label="t('settings.httpLogDays')" :tip="t('settings.httpLogDaysTooltip')" />
                  </div>
                  <n-input-number
                    v-model:value="appStore.httpLogDays"
                    :min="1"
                    :max="999"
                    size="small"
                    class="setting-item-control input-number-control"
                    @update:value="appStore.saveBasicConfig"
                  />
                </div>
              </div>
            </div>

            <div class="setting-item-row">
              <div class="setting-item-label">
                <SettingLabelTip :label="t('settings.minimalSkillMode')" :tip="t('settings.minimalSkillModeTooltip')" />
              </div>
              <n-switch
                v-model:value="appStore.minimalSkillMode"
                @update:value="appStore.saveBasicConfig"
              />
            </div>

            <div class="setting-item-row">
              <div class="setting-item-label">
                <SettingLabelTip :label="t('settings.password')">
                  <div>{{ appStore.passwordSet ? t('settings.passwordStatusSet') : t('settings.passwordStatusUnset') }}</div>
                  <div style="margin-top: 4px; color: var(--text-color-muted);">{{ t('settings.passwordTooltip') }}</div>
                </SettingLabelTip>
              </div>
              <!--
                密码刻意不设常驻输入框：常驻输入框会被浏览器自动填充回填既有值，
                后续保存其它设置时夹带提交会污染密码。改为按钮 + 独立弹窗现输现提。
              -->
              <div class="setting-password-wrapper">
                <n-button
                  v-if="!appStore.passwordSet"
                  type="primary"
                  size="small"
                  @click="openPasswordModal"
                >
                  {{ t('settings.passwordSetBtn') }}
                </n-button>
                <template v-else>
                  <n-button
                    type="primary"
                    size="small"
                    secondary
                    @click="openPasswordModal"
                  >
                    {{ t('settings.passwordModifyBtn') }}
                  </n-button>
                  <n-button
                    type="primary"
                    size="small"
                    secondary
                    @click="handleClearPassword"
                  >
                    {{ t('settings.passwordClearBtn') }}
                  </n-button>
                </template>
              </div>
            </div>
          </div>
        </n-tab-pane>

        <n-tab-pane name="providers" :tab="t('sider.providerSettings')">
          <div class="providers-pane-content">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
              <span style="font-size: 0.9rem; color: var(--text-color-muted);">{{ t('sider.providerListTitle') }}</span>
              <n-button type="primary" size="small" @click="openAddProvider">
                {{ t('sider.addProvider') }}
              </n-button>
            </div>

            <div class="provider-settings-list" style="display: flex; flex-direction: column; gap: 12px; max-height: 350px; overflow-y: auto; padding-right: 4px;">
              <div v-if="providerStore.providerList.length === 0" style="text-align: center; padding: 20px; color: var(--text-color-muted);">
                {{ t('sider.noProviders') }}
              </div>
              <template v-else>
                <!-- 按分组聚合展示：分组标题行 + 组内供应商卡片 -->
                <div v-for="g in groupedProviderList" :key="g.group" class="provider-group-block">
                  <div class="provider-group-header" style="display: flex; align-items: center; gap: 8px; padding: 4px 2px;">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--text-color-muted);">
                      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                    </svg>
                    <strong style="font-size: 0.82rem; color: var(--text-color-bright);">{{ g.group }}</strong>
                    <span style="font-size: 0.72rem; color: var(--text-color-muted); background-color: var(--overlay-veil); padding: 1px 6px; border-radius: 8px;">{{ g.items.length }}</span>
                  </div>
                  <div style="display: flex; flex-direction: column; gap: 8px; padding-left: 6px;">
                    <div
                      v-for="prov in g.items"
                      :key="prov.group + ':' + prov.modelName"
                      class="provider-setting-item"
                    >
                      <div>
                        <div style="display: flex; align-items: center; gap: 8px;">
                          <!-- 模型名称作为大标题（分组名已在分组标题行展示，无需重复） -->
                          <strong style="color: var(--text-color-bright);">{{ prov.modelName }}</strong>
                          <span class="protocol-badge">
                            {{ prov.protocol }}
                          </span>
                          <span v-if="prov.multimodal" class="multimodal-badge">
                            {{ t('sider.multimodalBadge') }}
                          </span>
                        </div>
                        <div style="font-size: 0.8rem; color: var(--text-color-muted); margin-top: 4px; display: flex; gap: 12px;">
                          <span v-if="prov.contextSize">{{ t('sider.window') }}: {{ prov.contextSize.toLocaleString() }} tokens</span>
                        </div>
                      </div>
                      <div style="display: flex; gap: 8px;">
                        <n-button type="primary" size="small" secondary @click="openCopyProvider(prov)">
                          {{ t('sider.copyProvider') }}
                        </n-button>
                        <n-button type="primary" size="small" secondary @click="openEditProvider(prov)">
                          {{ t('common.edit') }}
                        </n-button>
                        <n-popconfirm
                          @positive-click="providerStore.handleDeleteProvider(prov.group, prov.modelName)"
                          :positive-text="t('common.confirm')"
                          :negative-text="t('common.cancel')"
                          placement="top-end"
                        >
                          <template #trigger>
                            <n-button type="primary" size="small" secondary>
                              {{ t('common.delete') }}
                            </n-button>
                          </template>
                          {{ t('sider.deleteProviderConfirm') }}
                        </n-popconfirm>
                      </div>
                    </div>
                  </div>
                </div>
              </template>
            </div>
          </div>
        </n-tab-pane>

        <!-- 卡片3：关于 -->
        <n-tab-pane name="about" :tab="t('settings.aboutTab')">
          <div class="about-pane-content">
            <div class="about-card">
              <div class="about-header">
                <h2 class="about-title">{{ t('settings.aboutTitle') }}</h2>
                <div class="about-version-badge" :title="t('settings.aboutVersion')">
                  v{{ pkg.version }}
                </div>
              </div>
              <p class="about-subtitle">{{ t('settings.aboutSubtitle') }}</p>
            </div>
          </div>
        </n-tab-pane>
      </n-tabs>
    </div>

    <!--
      安全访问密码独立弹窗：现输现提，不与其它设置同批提交。
      密码值不进任何常驻表单状态，杜绝浏览器自动填充污染
    -->
    <n-modal
      v-model:show="showPasswordModal"
      preset="card"
      :title="appStore.passwordSet ? t('settings.passwordModifyTitle') : t('settings.passwordSetTitle')"
      style="width: 420px; max-width: 92vw; border: 1px solid var(--border-color); box-shadow: var(--shadow-overlay);"
    >
      <div style="display: flex; flex-direction: column; gap: 12px;">
        <!-- 明文显示：密码为本地新设置，不做遮蔽，便于用户核对输入；不做二次确认 -->
        <n-input
          v-model:value="passwordInput"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 1 }"
          :placeholder="t('settings.passwordInputPlaceholder')"
          size="medium"
          maxlength="32"
          spellcheck="false"
          autocomplete="new-password"
          @keydown.enter.prevent="handleSavePassword"
        />
        <div style="font-size: 0.75rem; color: var(--text-color-muted); line-height: 1.5;">
          {{ t('settings.passwordPolicyHint') }}
        </div>
      </div>
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px;">
          <n-button type="primary" size="small" :loading="passwordSaving" @click="handleSavePassword">
            {{ t('common.confirm') }}
          </n-button>
          <n-button size="small" @click="closePasswordModal">{{ t('common.cancel') }}</n-button>
        </div>
      </template>
    </n-modal>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { useResponsive } from '@/utils/useResponsive'
import { useAppStore } from '@/stores/app'
import { useProviderStore } from '@/stores/provider'
import SettingLabelTip from './SettingLabelTip.vue'
import { t, setLanguage } from '@/i18n'
import { switchTheme } from '@/styles/theme'
import type { ThemeName } from '@/styles/themeVars'
import pkg from '../../../../package.json'

/**
 * 系统设置弹窗（原 LeftSider.vue 内联的设置大弹窗整体抽离）。
 * 含：基础设置 Tab、供应商管理 Tab、供应商编辑子视图、密码独立弹窗。
 * 显隐由父组件 v-model:show 控制；打开时重置到主视图与基础 Tab。
 */
const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const { isMobile } = useResponsive()
const message = useMessage()
const dialog = useDialog()
const appStore = useAppStore()
const providerStore = useProviderStore()

const activeTab = ref('basic')
const isAddingOrEditingProvider = ref(false)
// 模型名称输入框引用：复制供应商时用于自动聚焦
const modelNameInputRef = ref<any>(null)

// 弹窗每次打开时回到主视图与基础 Tab（供应商编辑中途关闭后不残留子视图）
watch(() => props.show, (v) => {
  if (v) {
    isAddingOrEditingProvider.value = false
    activeTab.value = 'basic'
  } else {
    // 主弹窗关闭时联动收起内嵌密码弹窗，避免其独立残留悬浮
    closePasswordModal()
  }
})

const onLanguageChange = (val: 'zh-CN' | 'en-US') => {
  setLanguage(val)
  appStore.saveBasicConfig()
}

const onThemeChange = (val: ThemeName) => {
  switchTheme(val)
  appStore.saveBasicConfig()
}

// ── 安全访问密码弹窗 ──
// 密码仅在弹窗中现输现提：状态不经过任何常驻表单，避免浏览器自动填充污染密码值
const showPasswordModal = ref(false)
const passwordInput = ref('')
const passwordSaving = ref(false)

const openPasswordModal = () => {
  passwordInput.value = ''
  showPasswordModal.value = true
}

const closePasswordModal = () => {
  // 关闭即丢弃输入，密文不在内存中留存
  passwordInput.value = ''
  showPasswordModal.value = false
}

const handleSavePassword = async () => {
  if (passwordSaving.value) return
  passwordSaving.value = true
  try {
    const success = await appStore.savePassword(passwordInput.value)
    if (success) {
      closePasswordModal()
    }
  } finally {
    passwordSaving.value = false
  }
}

/**
 * 清除密码：二次确认后执行。
 * 清除后系统回到未启用密码保护的状态（仅本机来源可访问），故必须显式确认
 */
const handleClearPassword = () => {
  dialog.warning({
    title: t('settings.passwordClearConfirmTitle'),
    content: t('settings.passwordClearConfirmContent'),
    positiveText: t('common.confirm'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      await appStore.clearPassword()
    }
  })
}

// ── 供应商编辑子视图 ──
const openAddProvider = () => {
  providerStore.resetForm()
  isAddingOrEditingProvider.value = true
}

const openEditProvider = (prov: any) => {
  providerStore.handleEditProvider(prov)
  isAddingOrEditingProvider.value = true
}

const openCopyProvider = (prov: any) => {
  providerStore.handleCopyProvider(prov)
  isAddingOrEditingProvider.value = true
  // 复制场景强制改名：视图渲染完成后自动聚焦模型名称输入框，引导用户直接修改
  nextTick(() => {
    modelNameInputRef.value?.focus()
  })
}

// 供应商列表按分组聚合：分组按首次出现顺序排列，组内条目保持原有顺序
const groupedProviderList = computed(() => {
  const groups: { group: string; items: any[] }[] = []
  const groupIndexMap: Record<string, number> = {}
  providerStore.providerList.forEach(p => {
    const groupName = p.group || t('sider.defaultProvider')
    if (groupIndexMap[groupName] === undefined) {
      groupIndexMap[groupName] = groups.length
      groups.push({ group: groupName, items: [] })
    }
    groups[groupIndexMap[groupName]].items.push(p)
  })
  return groups
})

const cancelProviderEdit = () => {
  providerStore.resetForm()
  isAddingOrEditingProvider.value = false
}

const saveProviderAndReturn = async () => {
  const group = providerStore.form.group ? providerStore.form.group.trim() : ''
  const baseUrl = providerStore.form.baseUrl ? providerStore.form.baseUrl.trim() : ''
  const apiKey = providerStore.form.apiKey ? providerStore.form.apiKey.trim() : ''
  const modelName = providerStore.form.modelName ? providerStore.form.modelName.trim() : ''

  const protocol = providerStore.form.protocol
  if (!protocol) {
    message.warning(t('sider.protocolRequired'))
    return
  }
  if (!group) {
    message.warning(t('sider.groupRequired'))
    return
  }
  if (!baseUrl) {
    message.warning(t('sider.baseUrlRequired'))
    return
  }
  // 校验 Base URL 格式：必须以 http:// 或 https:// 开头，防止保存无效端点后所有请求失败
  if (!/^https?:\/\//.test(baseUrl)) {
    message.warning(t('sider.baseUrlInvalid'))
    return
  }
  if (!apiKey) {
    message.warning(t('sider.apiKeyRequired'))
    return
  }
  if (!modelName) {
    message.warning(t('sider.modelNameRequired'))
    return
  }
  if (!providerStore.form.contextSize) {
    message.warning(t('sider.contextSizeRequired'))
    return
  }

  // 校验分组名称：不能出现空格和特殊字符 (只允许字母、数字、下划线、连字符)
  const groupRegex = /^[a-zA-Z0-9_-]+$/
  if (!groupRegex.test(group)) {
    message.warning(t('sider.groupInvalidChar'))
    return
  }

  // 校验模型名称：不能出现空格和大部分特殊字符，但允许 @, /, -, _, ., :, 数字, 字母
  const modelRegex = /^[a-zA-Z0-9_./@:-]+$/
  if (!modelRegex.test(modelName)) {
    message.warning(t('sider.modelNameInvalidChar'))
    return
  }

  // 校验推理力度：枚举语义字段（如 low/medium/high/xhigh 等），仅允许字母、数字和连字符，
  // 防止乱填内容原样拼进大模型请求体导致接口报错
  if (providerStore.form.reasoningEffort && !/^[a-zA-Z0-9-]+$/.test(providerStore.form.reasoningEffort)) {
    message.warning(t('sider.reasoningEffortInvalidChar'))
    return
  }

  // 复制模式强制改名：模型名与源条目相同时不允许保存
  if (providerStore.copySourceModelName && modelName.toLowerCase() === providerStore.copySourceModelName.toLowerCase()) {
    message.warning(t('sider.copyMustRename'))
    return
  }

  // 同组同名即时查重：编辑模式排除自身，新增/复制模式全量比对
  if (providerStore.isModelNameDuplicated(group, modelName, providerStore.isEditing ? providerStore.originalModelName : undefined)) {
    message.warning(t('sider.modelDuplicate', { model: modelName, group }))
    return
  }

  providerStore.form.group = group
  providerStore.form.baseUrl = baseUrl
  providerStore.form.apiKey = apiKey
  providerStore.form.modelName = modelName

  await providerStore.handleSaveProvider()
  isAddingOrEditingProvider.value = false
}
</script>

<style scoped>
.settings-main-view {
  min-height: 360px;
  display: flex;
  flex-direction: column;
}
/* 系统设置弹窗中的顶部 Tab 栏及分割线优化 */
:deep(.settings-main-view .n-tabs.n-tabs--top-placement) {
  height: 100%;
  flex: 1;
  display: flex;
  flex-direction: column;
}
/* Tab 标签下方的水平分割线优化 */
:deep(.settings-main-view .n-tabs.n-tabs--top-placement > .n-tabs-nav) {
  margin-bottom: 12px;
  border-bottom: 1px solid var(--overlay-veil-strong);
  padding-bottom: 8px;
}
:deep(.settings-main-view .n-tabs.n-tabs--top-placement .n-tabs-pane-wrapper) {
  flex: 1;
}

/* 各设置面板内容区域容器 */
.settings-pane-content {
  padding: 0 24px;
  display: flex;
  flex-direction: column;
}
.providers-pane-content {
  padding: 16px 24px;
}

/* 设置项常规行式布局 */
.setting-item-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 0;
  border-bottom: 1px solid var(--overlay-veil-strong);
}
.setting-item-row--stack {
  /* 默认保持与 setting-item-row 一致，移动端再做堆叠覆盖 */
}

/* 设置项分组容器：父项为标准大选项行，开启后子项经树形连接线缩进挂接（如原始 HTTP 日志组） */
.setting-group {
  border-bottom: 1px solid var(--overlay-veil-strong);
}
.setting-group-parent {
  border-bottom: none;
}
/* 树形子项区：公共竖线按子项逐段绘制并精确止于最后一个分支，各子项经水平短线接入，形如文件树分支 */
.setting-tree-children {
  margin-left: 8px;
  padding-left: 18px;
}
.setting-tree-child {
  position: relative;
  padding: 12px 0;
  border-bottom: none;
}
/* 垂直段：贯穿本行全高，最后一个子项只画到中线（即分支接入点），形成树形收尾 */
.setting-tree-child::after {
  content: '';
  position: absolute;
  left: -18px;
  top: 0;
  height: 100%;
  width: 2px;
  border-radius: 1px;
  background-color: var(--overlay-veil-strong);
}
.setting-tree-child:last-child::after {
  height: 50%;
}
/* 水平支线：自垂直段接入本行中线 */
.setting-tree-child::before {
  content: '';
  position: absolute;
  left: -18px;
  top: 50%;
  width: 18px;
  height: 2px;
  transform: translateY(-1px);
  background-color: var(--overlay-veil-strong);
}
.setting-tree-child:last-child {
  padding-bottom: 16px;
}

/* 设置项标签容器 */
.setting-item-label {
  font-weight: 500;
  color: var(--text-color);
  display: flex;
  align-items: center;
  gap: 6px;
}
.setting-item-label-simple {
  font-weight: 500;
  color: var(--text-color);
}

/* 控制控件类 */
.setting-item-control.select-control {
  width: 260px;
}
.setting-item-control.input-number-control {
  width: 90px;
}

/* 设置项列式布局（如密码设置） */
.setting-item-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 0;
  border-bottom: 1px solid var(--overlay-veil-strong);
}
.setting-password-wrapper {
  display: flex;
  gap: 8px;
  align-items: center;
}
.setting-item-control.password-input {
  flex: 1;
}

/* 供应商条目卡片：令牌化配色（多主题就绪） */
.provider-setting-item {
  background-color: var(--overlay-veil);
  border: 1px solid var(--overlay-veil);
  border-radius: 8px;
  padding: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.protocol-badge {
  font-size: 0.75rem;
  background-color: var(--accent-bg-weak);
  color: var(--accent-color);
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid rgba(187, 187, 233, 0.35);
}

.multimodal-badge {
  font-size: 0.75rem;
  background-color: var(--accent-bg-weak);
  color: var(--accent-color);
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid rgba(187, 187, 233, 0.35);
}

/* 关于页面 */
.about-pane-content {
  padding: 48px 16px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.about-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}

.about-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.about-title {
  margin: 0;
  font-size: 2.2rem;
  font-weight: 800;
  letter-spacing: 1.5px;
  color: var(--text-color-bright);
}

.about-version-badge {
  font-size: 0.85rem;
  font-family: monospace;
  font-weight: bold;
  background-color: var(--accent-bg-weak);
  color: var(--accent-color);
  padding: 3px 10px;
  border-radius: 6px;
  border: 1px solid rgba(187, 187, 233, 0.35);
}

.about-subtitle {
  margin: 0;
  font-size: 1rem;
  font-weight: 500;
  color: var(--text-color);
  letter-spacing: 0.5px;
}

/* 移动端响应式适配 */
@media (max-width: 768px) {
  .settings-pane-content {
    padding: 0 12px;
  }
  .providers-pane-content {
    padding: 12px 16px;
  }
  .setting-item-row {
    padding: 12px 0;
  }
  /* 移动端强制堆叠为垂直布局的行 */
  .setting-item-row--stack {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .setting-item-row--stack .setting-item-control.select-control {
    width: 100%;
  }
  .setting-item-col {
    padding: 12px 0;
  }
}

/* 超窄屏幕适配（针对供应商项） */
@media (max-width: 480px) {
  .provider-setting-item {
    flex-direction: column !important;
    align-items: flex-start !important;
    gap: 8px !important;
    padding: 10px !important;
  }
  .provider-setting-item > div:last-child {
    align-self: flex-end;
  }
}
</style>
