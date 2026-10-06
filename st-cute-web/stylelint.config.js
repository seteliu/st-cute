// stylelint 配置
// 核心护栏：color-no-hex 零魔数值铁律——业务代码禁止裸 hex 色值，
// 例外通过底部 overrides 对主题变量登记处（main.css 全文件）整体豁免
/** @type {import('stylelint').Config} */
export default {
  extends: ['stylelint-config-standard'],
  plugins: ['stylelint-order'],
  ignoreFiles: ['dist/**', 'node_modules/**', 'pnpm-lock.yaml'],
  rules: {
    // ---------- 零魔数值铁律（固化决策 8 / 14） ----------
    // 全站禁止裸 hex 色值（main.css 例外见底部 overrides；themeVars.ts 是 .ts 不走 stylelint）
    'color-no-hex': true,

    // ---------- 与项目既定写法对齐的规则调整 ----------
    // 项目色彩契约统一为传统 rgba(r,g,b,a) 写法（themeVars.ts 与配色规范的事实标准），
    // 不迁移 modern notation / 百分比 alpha
    'color-function-notation': null,
    'color-function-alias-notation': null,
    'alpha-value-notation': null,
    // 配色规范明确使用 6 位 hex（textBright #ffffff 等），不缩写
    'color-hex-length': null,
    // 链式 :not(a):not(b) 写法浏览器兼容面更广，保持现状
    'selector-not-notation': null,
    // BEM 覆盖式组织（body.sider-resizing 拦截层在前）天然触发误报
    'no-descending-specificity': null,
    // word-break: break-word 虽标记废弃但兼容性最好，维持现状
    'declaration-property-value-keyword-no-deprecated': null,
    // 允许 SFC 保留空 style 块
    'no-empty-source': null,

    // ---------- standard 集适度放宽（对齐项目现状） ----------
    // 穿透选择器是 Vue scoped 深度选择器的常规形态
    'selector-pseudo-class-no-unknown': [
      true,
      { ignorePseudoClasses: ['deep', 'global', 'slotted'] }
    ],
    'selector-class-pattern': null,
    'keyframes-name-pattern': null,
    'property-no-vendor-prefix': null,
    'value-no-vendor-prefix': null,
    'media-feature-range-notation': null,

    // ---------- 属性排序（stylelint-order） ----------
    'order/properties-order': [],
    'order/properties-alphabetical-order': null
  },
  overrides: [
    {
      // .vue 单文件组件的 style 块
      files: ['**/*.vue'],
      customSyntax: 'postcss-html'
    },
    {
      // main.css：主题变量登记处（:root 定义与回退值），hex 色值合法
      files: ['**/assets/styles/main.css'],
      rules: {
        'color-no-hex': null
      }
    }
  ]
}
