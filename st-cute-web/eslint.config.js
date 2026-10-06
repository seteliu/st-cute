// ESLint 平面配置（flat config）
// 基础集策略：typescript-eslint recommended（不做类型检查，类型由 vue-tsc 独立承担）+
// vue3-recommended + prettier 兼容关闭，保持构建与 lint 职责分离
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import configPrettier from 'eslint-config-prettier'
import globals from 'globals'

export default tseslint.config(
  // 全局忽略
  { ignores: ['dist/**', 'node_modules/**'] },

  // JS 基础
  js.configs.recommended,

  // Vue SFC：vue3 推荐集（含模板规则与 vue-eslint-parser）
  ...pluginVue.configs['flat/recommended'],

  // TS 基础集（无类型检查版）——限定 .ts 文件，避免覆盖 .vue 的模板解析器
  ...tseslint.configs.recommended.map((c) => ({ ...c, files: ['**/*.ts'] })),

  // .vue 文件：<script> 内嵌 TS 由 vue-eslint-parser 委托给 TS parser
  {
    files: ['**/*.vue'],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        parser: tseslint.parser,
        sourceType: 'module',
        extraFileExtensions: ['.vue']
      }
    },
    rules: {
      // 基础 no-undef / no-unused-vars 对模板内联表达式与 TS 代码重复报告，
      // 统一交由 @typescript-eslint/no-unused-vars 收口
      'no-undef': 'off',
      'no-unused-vars': 'off'
    }
  },

  // .ts 文件：浏览器环境全局变量（window/document 等）
  {
    files: ['**/*.ts'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node }
    }
  },

  // 项目规则
  {
    // @typescript-eslint 规则需显式挂插件（TS recommended 集已被限定 files 到 .ts，
    // 插件绑定不随规则块全局生效）
    plugins: { '@typescript-eslint': tseslint.plugin },
    rules: {
      // 单名单页面组件（Login 等）合法，不强制多词名
      'vue/multi-word-component-names': 'off',
      // v-html 统一走 renderMarkdownSafe（DOMPurify 消毒，已有单测锁定防线），放行
      'vue/no-v-html': 'off',
      // App.vue 存在局部注册的内联子组件，属合法形态
      'vue/one-component-per-file': 'off',
      // TS defineProps 声明式风格无需运行时默认值
      'vue/require-default-prop': 'off',
      // 存量 any 集中在 WS 载荷与第三方全局对象（VirtualChatList 收敛已在待办），暂不收口
      '@typescript-eslint/no-explicit-any': 'off',
      // 未使用变量：忽略下划线前缀与 catch 形参（catch (e) 不要求消费），聚焦死导入
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' }
      ],
      // 空 catch 块：允许静默吞错的既有模式（新增代码仍会被 vue-tsc 与 review 把关）
      'no-empty': ['error', { allowEmptyCatch: true }],
      // 与 prettier 冲突的格式规则全部关闭
      ...configPrettier.rules
    }
  }
)
