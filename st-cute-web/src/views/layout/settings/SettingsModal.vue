<template>
  <!-- 系统设置大弹窗：升级进行中锁定关闭（closable/遮罩点击/ESC 全禁），防止用户中途丢下进度现场 -->
  <n-modal
    :show="show"
    :auto-focus="false"
    preset="card"
    :closable="!upgradeInProgress"
    :mask-closable="!upgradeInProgress"
    :close-on-esc="!upgradeInProgress"
    style="
      width: 760px;
      max-width: 95vw;
      min-height: 480px;
      border: 1px solid var(--border-color);
      box-shadow: var(--shadow-overlay);
    "
    :title="
      isAddingOrEditingProvider
        ? providerStore.isEditing
          ? t('sider.editProvider')
          : providerStore.copySourceModelName
            ? t('sider.copyProviderTitle')
            : t('sider.addProvider')
        : t('settings.title')
    "
    size="medium"
    @update:show="(v: boolean) => (upgradeInProgress ? undefined : emit('update:show', v))"
  >
    <!-- 供应商专属子页面 -->
    <div v-if="isAddingOrEditingProvider" class="provider-sub-view">
      <n-form
        :label-placement="isMobile ? 'top' : 'left'"
        label-width="135"
        style="margin-top: 15px"
      >
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px">*</span>
            {{ t('sider.protocol') }}
          </template>
          <n-select
            v-model:value="providerStore.form.protocol"
            :options="providerStore.protocolOptions"
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px">*</span>
            {{ t('sider.groupName') }} (Group)
          </template>
          <n-input
            v-model:value="providerStore.form.group"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.groupPlaceholder')"
            spellcheck="false"
            maxlength="50"
            @keydown.enter.prevent
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px">*</span>
            {{ t('sider.modelName') }}
          </template>
          <n-input
            ref="modelNameInputRef"
            v-model:value="providerStore.form.modelName"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.modelNamePlaceholder')"
            spellcheck="false"
            maxlength="100"
            @keydown.enter.prevent
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px">*</span>
            {{ t('sider.baseUrl') }}
          </template>
          <div style="display: flex; flex-direction: column; width: 100%; gap: 6px">
            <n-input
              v-model:value="providerStore.form.baseUrl"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 1 }"
              :placeholder="t('sider.baseUrlPlaceholder')"
              spellcheck="false"
              maxlength="255"
              @keydown.enter.prevent
            />
            <n-checkbox v-model:checked="providerStore.form.useFullUrl">
              {{ t('sider.useFullUrl') }}
            </n-checkbox>
          </div>
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px">*</span>
            {{ t('sider.apiKey') }}
          </template>
          <n-input
            v-model:value="providerStore.form.apiKey"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.apiKeyPlaceholder')"
            spellcheck="false"
            maxlength="255"
            @keydown.enter.prevent
          />
        </n-form-item>
        <n-form-item>
          <template #label>
            <span style="color: var(--status-error); margin-right: 4px">*</span>
            {{ t('sider.contextSize') }}
          </template>
          <n-input-number
            v-model:value="providerStore.form.contextSize"
            :min="50000"
            :max="10000000"
            :step="1000"
            :placeholder="t('sider.contextSizePlaceholder')"
            style="width: 100%"
            :input-props="{ spellcheck: 'false' }"
          />
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
            style="width: 100%"
          />
        </n-form-item>
        <n-form-item :label="t('sider.reasoningEffort')">
          <n-input
            v-model:value="providerStore.form.reasoningEffort"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 1 }"
            :placeholder="t('sider.reasoningEffortPlaceholder')"
            spellcheck="false"
            maxlength="50"
            :clearable="true"
            @keydown.enter.prevent
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
            style="width: 100%"
          />
        </n-form-item>
      </n-form>
      <div style="display: flex; justify-content: flex-end; gap: 12px; margin-top: 20px">
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
                <SettingLabelTip
                  :label="t('settings.language')"
                  :tip="t('settings.languageTooltip')"
                />
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
                <SettingLabelTip
                  :label="t('settings.newlineKey')"
                  :tip="t('settings.newlineKeyTooltip')"
                />
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
                <SettingLabelTip
                  :label="t('settings.pathSandbox')"
                  :tip="t('settings.pathSandboxTooltip')"
                />
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
                  <SettingLabelTip
                    :label="t('settings.httpLog')"
                    :tip="t('settings.httpLogTooltip')"
                  />
                </div>
                <n-switch
                  v-model:value="appStore.httpLog"
                  @update:value="appStore.saveBasicConfig"
                />
              </div>
              <div v-if="appStore.httpLog" class="setting-tree-children">
                <div class="setting-item-row setting-tree-child">
                  <div class="setting-item-label">
                    <SettingLabelTip
                      :label="t('settings.httpLogIncludeResponse')"
                      :tip="t('settings.httpLogIncludeResponseTooltip')"
                    />
                  </div>
                  <n-switch
                    v-model:value="appStore.httpLogIncludeResponse"
                    @update:value="appStore.saveBasicConfig"
                  />
                </div>
                <div class="setting-item-row setting-tree-child">
                  <div class="setting-item-label">
                    <SettingLabelTip
                      :label="t('settings.httpLogDays')"
                      :tip="t('settings.httpLogDaysTooltip')"
                    />
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
                <SettingLabelTip
                  :label="t('settings.minimalSkillMode')"
                  :tip="t('settings.minimalSkillModeTooltip')"
                />
              </div>
              <n-switch
                v-model:value="appStore.minimalSkillMode"
                @update:value="appStore.saveBasicConfig"
              />
            </div>

            <div class="setting-item-row">
              <div class="setting-item-label">
                <SettingLabelTip :label="t('settings.password')">
                  <div>
                    {{
                      appStore.passwordSet
                        ? t('settings.passwordStatusSet')
                        : t('settings.passwordStatusUnset')
                    }}
                  </div>
                  <div style="margin-top: 4px; color: var(--text-color-muted)">
                    {{ t('settings.passwordTooltip') }}
                  </div>
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
                  <n-button type="primary" size="small" secondary @click="openPasswordModal">
                    {{ t('settings.passwordModifyBtn') }}
                  </n-button>
                  <n-button type="primary" size="small" secondary @click="handleClearPassword">
                    {{ t('settings.passwordClearBtn') }}
                  </n-button>
                </template>
              </div>
            </div>
          </div>
        </n-tab-pane>

        <n-tab-pane name="providers" :tab="t('sider.providerSettings')">
          <div class="providers-pane-content">
            <div
              style="
                display: flex;
                justify-content: space-between;
                align-items: center;
                margin-bottom: 16px;
              "
            >
              <span style="font-size: 0.9rem; color: var(--text-color-muted)">
                {{ t('sider.providerListTitle') }}
              </span>
              <n-button type="primary" size="small" @click="openAddProvider">
                {{ t('sider.addProvider') }}
              </n-button>
            </div>

            <div
              class="provider-settings-list"
              style="
                display: flex;
                flex-direction: column;
                gap: 12px;
                max-height: 350px;
                overflow-y: auto;
                padding-right: 4px;
              "
            >
              <div
                v-if="providerStore.providerList.length === 0"
                style="text-align: center; padding: 20px; color: var(--text-color-muted)"
              >
                {{ t('sider.noProviders') }}
              </div>
              <template v-else>
                <!-- 按分组聚合展示：分组标题行 + 组内供应商卡片 -->
                <div v-for="g in groupedProviderList" :key="g.group" class="provider-group-block">
                  <div
                    class="provider-group-header"
                    style="display: flex; align-items: center; gap: 8px; padding: 4px 2px"
                  >
                    <svg
                      xmlns="http://www.w3.org/2000/svg"
                      viewBox="0 0 24 24"
                      width="14"
                      height="14"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      style="color: var(--text-color-muted)"
                    >
                      <path
                        d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"
                      ></path>
                    </svg>
                    <strong style="font-size: 0.82rem; color: var(--text-color-bright)">
                      {{ g.group }}
                    </strong>
                    <span
                      style="
                        font-size: 0.72rem;
                        color: var(--text-color-muted);
                        background-color: var(--overlay-veil);
                        padding: 1px 6px;
                        border-radius: 8px;
                      "
                    >
                      {{ g.items.length }}
                    </span>
                  </div>
                  <div style="display: flex; flex-direction: column; gap: 8px; padding-left: 6px">
                    <div
                      v-for="prov in g.items"
                      :key="prov.group + ':' + prov.modelName"
                      class="provider-setting-item"
                    >
                      <div>
                        <div style="display: flex; align-items: center; gap: 8px">
                          <!-- 模型名称作为大标题（分组名已在分组标题行展示，无需重复） -->
                          <strong style="color: var(--text-color-bright)">
                            {{ prov.modelName }}
                          </strong>
                          <span class="protocol-badge">
                            {{ prov.protocol }}
                          </span>
                          <span v-if="prov.multimodal" class="multimodal-badge">
                            {{ t('sider.multimodalBadge') }}
                          </span>
                        </div>
                        <div
                          style="
                            font-size: 0.8rem;
                            color: var(--text-color-muted);
                            margin-top: 4px;
                            display: flex;
                            gap: 12px;
                          "
                        >
                          <span v-if="prov.contextSize">
                            {{ t('sider.window') }}: {{ prov.contextSize.toLocaleString() }} tokens
                          </span>
                        </div>
                      </div>
                      <div style="display: flex; gap: 8px">
                        <n-button
                          type="primary"
                          size="small"
                          secondary
                          @click="openCopyProvider(prov)"
                        >
                          {{ t('sider.copyProvider') }}
                        </n-button>
                        <n-button
                          type="primary"
                          size="small"
                          secondary
                          @click="openEditProvider(prov)"
                        >
                          {{ t('common.edit') }}
                        </n-button>
                        <n-popconfirm
                          :positive-text="t('common.confirm')"
                          :negative-text="t('common.cancel')"
                          placement="top-end"
                          @positive-click="
                            providerStore.handleDeleteProvider(prov.group, prov.modelName)
                          "
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
              </div>
              <p class="about-subtitle">{{ t('settings.aboutSubtitle') }}</p>

              <!-- 运行信息：运行方式与底座/应用版本 -->
              <div class="about-info-table">
                <div class="about-info-row">
                  <span class="about-info-label">{{ t('settings.aboutRunMode') }}</span>
                  <span class="about-info-value">
                    <n-tag :bordered="false" size="small" :type="launcherTagType">
                      {{ updateInfo?.launcherTypeName || '—' }}
                    </n-tag>
                  </span>
                </div>
                <div class="about-info-row">
                  <span class="about-info-label">{{ t('settings.aboutLauncherVersion') }}</span>
                  <span class="about-info-value">{{
                    updateInfo?.launcherVersion || t('settings.aboutLauncherVersionUnknown')
                  }}</span>
                </div>
                <div class="about-info-row">
                  <span class="about-info-label">{{ t('settings.aboutJarVersion') }}</span>
                  <span class="about-info-value">{{ updateInfo?.currentVersion ?? '—' }}</span>
                </div>
              </div>

              <!-- 更新检查区块：按五态结论渲染 -->
              <div class="about-update-section">
                <div class="about-update-title">{{ t('settings.aboutUpdateSectionTitle') }}</div>

                <!-- 检查中 -->
                <div v-if="updateChecking" class="about-update-body">
                  <n-spin :size="14" />
                  <span>{{ t('settings.updateChecking') }}</span>
                </div>

                <template v-else-if="updateInfo">
                  <!-- 检查失败 -->
                  <div v-if="updateInfo.status === 'CHECK_FAIL'" class="about-update-body">
                    <span class="about-update-warn">{{ t('settings.updateCheckFailed') }}</span>
                    <n-button size="tiny" :loading="updateChecking" @click="handleRecheckUpdate">
                      {{ t('settings.updateRecheck') }}
                    </n-button>
                  </div>

                  <!-- 手动运行：不支持自动升级 -->
                  <div v-else-if="updateInfo.status === 'UNSUPPORTED'" class="about-update-body">
                    <span>{{ t('settings.updateUnsupportedTip') }}</span>
                  </div>

                  <!-- 旧底座：引导前往发布页整包更新 -->
                  <div v-else-if="updateInfo.status === 'OLD_BASE'" class="about-update-body">
                    <span class="about-update-warn">
                      {{ updateInfo.oldBaseTip || t('settings.updateOldBaseTip') }}
                    </span>
                    <n-button size="tiny" type="warning" @click="openReleasePage">
                      {{ t('settings.updateOldBaseGoRelease') }}
                    </n-button>
                  </div>

                  <!-- 有新版：展示版本对比与更新说明 -->
                  <div v-else-if="updateInfo.status === 'UPDATABLE'" class="about-update-body">
                    <span class="about-update-ok">{{ t('settings.updateUpdatableTip') }}</span>
                    <div class="about-update-versions">
                      {{ updateInfo.currentVersion }} → {{ updateInfo.latestVersion }}
                    </div>
                    <pre v-if="updateInfo.notes" class="about-update-notes">{{
                      updateInfo.notes
                    }}</pre>
                    <n-button
                      v-if="upgradePhase === 'idle'"
                      type="primary"
                      size="small"
                      :loading="updateApplying"
                      @click="handleApplyUpdate"
                    >
                      {{ t('settings.updateBtn') }}
                    </n-button>
                    <!-- 升级执行中：进度条与阶段文案 -->
                    <div v-else-if="upgradePhase === 'downloading'" class="about-upgrade-progress">
                      <n-progress
                        v-if="!upgradeDownloadedText"
                        type="line"
                        :percentage="upgradePercent"
                        :height="8"
                        :show-indicator="false"
                      />
                      <span>{{
                        upgradeDownloadedText
                          ? t('settings.updateDownloadingBytes', { size: upgradeDownloadedText })
                          : t('settings.updateDownloading') + ' ' + upgradePercent + '%'
                      }}</span>
                    </div>
                    <div v-else-if="upgradePhase === 'downloaded'" class="about-upgrade-progress">
                      <n-spin :size="14" />
                      <span>{{ t('settings.updateDownloaded') }}</span>
                    </div>
                    <!-- 重启等待态：服务以退出码 88 重启期间，就绪后整页刷新进新版 -->
                    <div v-else-if="upgradePhase === 'restarting'" class="about-upgrade-progress">
                      <n-spin :size="16" />
                      <span class="about-update-ok">{{ t('settings.updateRestarting') }}</span>
                      <span class="about-restart-tip">
                        {{ t('settings.updateRestartingTip') }}
                      </span>
                    </div>
                  </div>

                  <!-- 已最新 -->
                  <div v-else class="about-update-body">
                    <span class="about-update-ok">{{ t('settings.updateLatestTip') }}</span>
                  </div>

                  <!-- 上次升级失败提示（24h 内）：浏览器离线错过事件也能感知，可重试 -->
                  <div v-if="updateInfo.lastFailureReason" class="about-update-lastfail">
                    <span>{{
                      t('settings.updateLastFailed', { reason: updateInfo.lastFailureReason })
                    }}</span>
                  </div>

                  <!-- 旧底座态附最小底座要求说明（底座达标时不展示，避免无信息量的常驻噪音） -->
                  <div
                    v-if="updateInfo.status === 'OLD_BASE' && updateInfo.minBaseVersion"
                    class="about-update-minbase"
                  >
                    {{ t('settings.updateMinBase', { version: updateInfo.minBaseVersion }) }}
                  </div>
                </template>
              </div>
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
      :title="
        appStore.passwordSet ? t('settings.passwordModifyTitle') : t('settings.passwordSetTitle')
      "
      style="
        width: 420px;
        max-width: 92vw;
        border: 1px solid var(--border-color);
        box-shadow: var(--shadow-overlay);
      "
    >
      <div style="display: flex; flex-direction: column; gap: 12px">
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
        <div style="font-size: 0.75rem; color: var(--text-color-muted); line-height: 1.5">
          {{ t('settings.passwordPolicyHint') }}
        </div>
      </div>
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button
            type="primary"
            size="small"
            :loading="passwordSaving"
            @click="handleSavePassword"
          >
            {{ t('common.confirm') }}
          </n-button>
          <n-button size="small" @click="closePasswordModal">{{ t('common.cancel') }}</n-button>
        </div>
      </template>
    </n-modal>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch, onMounted, onBeforeUnmount } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { useResponsive } from '@/utils/useResponsive'
import { useAppStore } from '@/stores/app'
import { useProviderStore } from '@/stores/provider'
import SettingLabelTip from './SettingLabelTip.vue'
import { t, setLanguage } from '@/i18n'
import { switchTheme } from '@/styles/theme'
import type { ThemeName } from '@/styles/themeVars'
import { checkUpdateApi, applyUpdateApi, getUpdateStatusApi } from '@/api/update'
import type { UpdateCheckResult } from '@/types'
import { WS_EVENTS, LOCAL_EVENTS } from '@/constants/ws-events'
import { wsService } from '@/services/websocket'

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
watch(
  () => props.show,
  (v) => {
    if (v) {
      isAddingOrEditingProvider.value = false
      activeTab.value = 'basic'
    } else {
      // 主弹窗关闭时联动收起内嵌密码弹窗，避免其独立残留悬浮
      closePasswordModal()
    }
  }
)

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

// ── 关于页 · 更新检查 ──
// 进入关于页即触发检查（5 分钟内重复进入命中后端缓存）；检查失败不弹全局错误，
// 在区块内展示失败态与重试入口（失败请求配置为 silent）
const updateInfo = ref<UpdateCheckResult | null>(null)
const updateChecking = ref(false)

/** 运行方式标签配色：桌面端/官方脚本为正常色，手动运行为中性灰 */
const launcherTagType = computed(() =>
  updateInfo.value?.launcherType === 'DESKTOP' || updateInfo.value?.launcherType === 'SCRIPT'
    ? 'success'
    : 'default'
)

/** 拉取更新检查结果（失败静默，由区块内失败态渲染）；升级进行中时跳过，防止 check 渲染覆盖进度态 */
const fetchUpdateInfo = async (refresh = false) => {
  if (updateChecking.value || upgradePhase.value !== 'idle') return
  updateChecking.value = true
  try {
    updateInfo.value = await checkUpdateApi(refresh)
  } catch {
    updateInfo.value = null
  } finally {
    updateChecking.value = false
  }
}

/** 「重新检查」：绕过服务端缓存强制回源 */
const handleRecheckUpdate = () => {
  fetchUpdateInfo(true)
}

/** 旧底座场景：打开发布页手动下载完整安装包 */
const openReleasePage = () => {
  const url = updateInfo.value?.releaseUrl
  if (url) {
    window.open(url, '_blank', 'noopener')
  }
}

// 进入 about Tab 时触发检查；每次重新进入都刷新（后端缓存兜底防抖动）
watch(activeTab, (tab) => {
  if (tab === 'about') {
    fetchUpdateInfo()
    restoreUpgradingState()
  }
})

/**
 * 恢复进行中的升级展示：刷新页面/新开标签页后 WS 进度虽丢失，
 * 但后端会话锁仍在——status 探测到进行中即切到下载态（进度条自 0 起步，
 * 后续由 WS 广播续推；重启等待态由 RESTARTING 广播与 HTTP 返回驱动，均不受影响）
 */
const restoreUpgradingState = async () => {
  try {
    const upgradingNow = await getUpdateStatusApi()
    if (upgradingNow && upgradePhase.value === 'idle') {
      updateApplying.value = true
      upgradePhase.value = 'downloading'
    }
  } catch {
    // 探测失败不阻塞：按无进行中升级处理
  }
}

// ── 升级执行流程 ──
// 状态机：idle → downloading(ws 进度) → downloaded → restarting(重启等待,就绪后整页刷新) → 失败回 idle
const updateApplying = ref(false)
const upgradePhase = ref<'idle' | 'downloading' | 'downloaded' | 'restarting'>('idle')
const upgradePercent = ref(0)
/** 总长未知时的下载量文本（如 "12.3 MB"），与百分比互斥展示 */
const upgradeDownloadedText = ref('')

/** 升级进行中（含重启等待）：设置弹窗锁定不可关闭，防止用户中途丢下进度现场 */
const upgradeInProgress = computed(() => upgradePhase.value !== 'idle' || updateApplying.value)

/** 点击「开始更新」：二次确认（对话任务将中断）后发起 apply；信息型蓝色调（升级是常规操作非危险操作） */
const handleApplyUpdate = () => {
  dialog.info({
    title: t('settings.updateConfirmTitle'),
    content: t('settings.updateConfirmContent'),
    positiveText: t('common.confirm'),
    negativeText: t('common.cancel'),
    onPositiveClick: () => {
      // 不 await：apply 的 HTTP 响应要等后端整个下载完成才返回，若在此等待，
      // naive-ui 会保持确认弹窗打开（按钮 loading）直至下载结束，进度 UI 被弹窗压住
      // 整场不可见。改为即刻关窗放行，进度条接管展示；后续状态由 WS 广播与 HTTP 返回驱动
      startUpgrade()
    }
  })
}

const startUpgrade = async () => {
  if (updateApplying.value) return
  updateApplying.value = true
  upgradePhase.value = 'downloading'
  upgradePercent.value = 0
  upgradeDownloadedText.value = ''
  // 记录升级前版本：重启轮询以「版本已切换」为就绪信号（兼容升级成功与回滚两种终态）
  restartBeforeVersion = updateInfo.value?.currentVersion || ''
  try {
    // 受理成功即视为下载中；HTTP 响应在后端下载全程结束后返回（返回后进入重启等待），
    // 后续进度由 WS 广播驱动。服务退出瞬间 WS 可能不可靠，故 HTTP 返回即切 restarting 态兜底
    await applyUpdateApi()
    upgradePhase.value = 'restarting'
    startPingPolling()
  } catch (e: any) {
    // 前置失败（不支持/并发占用/元数据异常）：回到待更新态并提示
    upgradePhase.value = 'idle'
    updateApplying.value = false
    message.error(e?.message || t('settings.updateFailed'))
  }
}

/** WS 进度回调：downloading 百分比/字节数推进 / downloaded 就绪 / failed 失败收尾 */
const onUpgradeProgress = (event: any) => {
  const payload = event?.payload || {}
  if (payload.phase === 'downloading') {
    upgradePhase.value = 'downloading'
    if (payload.percent !== null && payload.percent !== undefined) {
      upgradePercent.value = Number(payload.percent) || 0
    } else if (payload.detail) {
      // 总长未知（代理强制 gzip 等）：后端降级推送已读字节数，进度条改为下载量展示
      upgradeDownloadedText.value = String(payload.detail)
    }
  } else if (payload.phase === 'downloaded') {
    // 下载完成：仅当下一步尚未进入重启等待时更新展示（RESTARTING 可能已先行到达）
    if (upgradePhase.value === 'downloading') {
      upgradePhase.value = 'downloaded'
    }
  } else if (payload.phase === 'failed') {
    upgradePhase.value = 'idle'
    updateApplying.value = false
    message.error(payload.detail || t('settings.updateFailed'))
  } else if (payload.phase === 'completed') {
    // 新版启动成功握手（重启后本页经整页刷新重建，理论上收不到；兜底处理）
    message.success(t('settings.updateSuccessTip', { version: payload.detail || '' }))
  }
}

/** WS 重启预告：切换重启等待态并开始轮询 /api/ping（与 HTTP 返回路径双保险） */
const onUpgradeRestarting = () => {
  upgradePhase.value = 'restarting'
  startPingPolling()
}

/** restarting 期间的 WS 重连就绪信号：服务重启后物理连接断开并自动重连，
 * OPEN 即后端已回来——覆盖"版本未变化"的回滚终态（此时版本判据永不满足，
 * 会挂满 3 分钟超时），也顺带纠正非发起页基线预取瞬断导致的过早刷新 */
let restartWsOpenCb: (() => void) | null = null

const armRestartWsSignal = () => {
  restartWsOpenCb = () => {
    stopPingPolling()
    window.location.reload()
  }
  wsService.on(LOCAL_EVENTS.OPEN, restartWsOpenCb)
}

const disarmRestartWsSignal = () => {
  if (restartWsOpenCb) {
    wsService.off(LOCAL_EVENTS.OPEN, restartWsOpenCb)
    restartWsOpenCb = null
  }
}

let pingPollingTimer: ReturnType<typeof setInterval> | null = null

/** 轮询白名单接口 /api/ping：版本发生切换（升级成功）或 WS 重连（回滚终态版本不变）后整页刷新 */
const startPingPolling = async () => {
  if (pingPollingTimer) clearInterval(pingPollingTimer)
  armRestartWsSignal()
  // 空基线兜底（多标签页场景）：本页未发起过升级时 restartBeforeVersion 为空，
  // 直接比较会在服务尚未退出的首次 ping 就误判"版本切换"而过早刷新；
  // 先取一次当前版本作基线再进入轮询
  if (!restartBeforeVersion) {
    try {
      const res = await fetch('/api/ping')
      const body = await res.ok ? await res.json() : null
      if (body?.code === 0 && body?.data?.version) {
        restartBeforeVersion = String(body.data.version)
      }
    } catch {
      // 服务可能已在重启中：保持空基线，靠下方"版本非空即切换"语义兜底
    }
  }
  const startedAt = Date.now()
  pingPollingTimer = setInterval(async () => {
    // 超时兜底：3 分钟仍未就绪（可能已回滚到旧版），放弃遮罩整页刷新由用户自行查看
    if (Date.now() - startedAt > 3 * 60 * 1000) {
      stopPingPolling()
      window.location.reload()
      return
    }
    try {
      const res = await fetch('/api/ping')
      if (!res.ok) return
      const body = await res.json()
      if (body?.code === 0 && body?.data?.app === 'st-cute') {
        // 版本已发生切换（升级成功到目标版，或超时回滚到旧版）：整页刷新进入最终状态
        const version = String(body.data.version || '')
        if (version && version !== restartBeforeVersion) {
          stopPingPolling()
          window.location.reload()
        }
      }
    } catch {
      // 服务重启中连接拒绝：继续轮询
    }
  }, 1500)
}

/** 记录发起升级前的版本（用于判定版本已发生切换） */
let restartBeforeVersion = ''

const stopPingPolling = () => {
  disarmRestartWsSignal()
  if (pingPollingTimer) {
    clearInterval(pingPollingTimer)
    pingPollingTimer = null
  }
}

// 挂载 WS 监听（组件常驻于设置弹窗内，随根实例存活；卸载时移除）
onMounted(() => {
  wsService.on(WS_EVENTS.UPDATE_PROGRESS, onUpgradeProgress)
  wsService.on(WS_EVENTS.UPDATE_RESTARTING, onUpgradeRestarting)
})
onBeforeUnmount(() => {
  wsService.off(WS_EVENTS.UPDATE_PROGRESS, onUpgradeProgress)
  wsService.off(WS_EVENTS.UPDATE_RESTARTING, onUpgradeRestarting)
  stopPingPolling()
})

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
  providerStore.providerList.forEach((p) => {
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
  if (
    providerStore.form.reasoningEffort &&
    !/^[a-zA-Z0-9-]+$/.test(providerStore.form.reasoningEffort)
  ) {
    message.warning(t('sider.reasoningEffortInvalidChar'))
    return
  }

  // 复制模式强制改名：模型名与源条目相同时不允许保存
  if (
    providerStore.copySourceModelName &&
    modelName.toLowerCase() === providerStore.copySourceModelName.toLowerCase()
  ) {
    message.warning(t('sider.copyMustRename'))
    return
  }

  // 同组同名即时查重：编辑模式排除自身，新增/复制模式全量比对
  if (
    providerStore.isModelNameDuplicated(
      group,
      modelName,
      providerStore.isEditing ? providerStore.originalModelName : undefined
    )
  ) {
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
  /* 固定高度消除异步注入闪动（检查中占位 → 五态内容高度不同），弹窗总高恒定。
     440px 容纳常态内容（标题+信息表+五态区块+失败提示叠加）不出滚动条，
     仅 UPDATABLE 携带长 notes 的极端场景内部滚动；上下留白 32px 由垂直居中吸收 */
  height: 440px;
  overflow-y: auto;
  padding: 32px 16px;
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

.about-subtitle {
  margin: 0;
  font-size: 1rem;
  font-weight: 500;
  color: var(--text-color);
  letter-spacing: 0.5px;
}

/* 关于页 · 运行信息表 */
.about-info-table {
  width: 100%;
  max-width: 360px;
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.about-info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  border-radius: 6px;
  background-color: var(--bg-color-hover, rgba(128, 128, 128, 0.08));
}

.about-info-label {
  font-size: 0.85rem;
  color: var(--text-color-muted);
}

.about-info-value {
  font-size: 0.85rem;
  font-family: monospace;
  color: var(--text-color);
}

/* 关于页 · 更新检查区块 */
.about-update-section {
  width: 100%;
  max-width: 360px;
  margin-top: 16px;
  padding: 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.about-update-title {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-color-bright);
}

.about-update-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  font-size: 0.85rem;
  color: var(--text-color);
  text-align: center;
}

.about-update-warn {
  color: var(--status-error, #d03050);
}

.about-update-ok {
  color: var(--status-success, #18a058);
}

.about-update-versions {
  font-family: monospace;
  color: var(--text-color-bright);
}

.about-update-notes {
  margin: 0;
  max-height: 120px;
  overflow-y: auto;
  width: 100%;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 0.8rem;
  line-height: 1.6;
  color: var(--text-color);
  background-color: var(--bg-color-hover, rgba(128, 128, 128, 0.08));
  padding: 8px;
  border-radius: 6px;
}

.about-update-minbase {
  font-size: 0.75rem;
  color: var(--text-color-muted);
  text-align: center;
}

/* 上次升级失败提示（24h 内） */
.about-update-lastfail {
  font-size: 0.75rem;
  color: var(--status-error, #d03050);
  text-align: center;
  line-height: 1.5;
  padding: 4px 8px;
  border-radius: 4px;
  background-color: var(--bg-color-hover, rgba(128, 128, 128, 0.08));
}

/* 关于页 · 升级执行进度 */
.about-upgrade-progress {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  font-size: 0.8rem;
  color: var(--text-color);
}

/* 重启等待态的说明文字 */
.about-restart-tip {
  font-size: 0.75rem;
  color: var(--text-color-muted);
  text-align: center;
  line-height: 1.5;
  max-width: 300px;
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
