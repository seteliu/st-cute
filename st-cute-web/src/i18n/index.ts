import { ref } from 'vue'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'

export type Language = 'zh-CN' | 'en-US'

export const currentLang = ref<Language>('zh-CN')

const messages: Record<Language, any> = {
  'zh-CN': zhCN,
  'en-US': enUS
}

export function setLanguage(lang: Language) {
  if (messages[lang]) {
    currentLang.value = lang
  }
}

export function getLanguage(): Language {
  return currentLang.value
}

export function t(key: string, args?: Record<string, any>): string {
  const keys = key.split('.')
  let res: any = messages[currentLang.value]
  for (const k of keys) {
    if (res && res[k] !== undefined) {
      res = res[k]
    } else {
      let fallbackRes: any = messages['zh-CN']
      for (const fk of keys) {
        if (fallbackRes && fallbackRes[fk] !== undefined) {
          fallbackRes = fallbackRes[fk]
        } else {
          return key
        }
      }
      res = fallbackRes
      break
    }
  }
  if (typeof res === 'string' && args) {
    return res.replace(/\{(\w+)\}/g, (_, p1) => args[p1] !== undefined ? String(args[p1]) : `{${p1}}`)
  }
  return typeof res === 'string' ? res : key
}

export const i18n = {
  global: {
    locale: currentLang,
    t
  }
}
