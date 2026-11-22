import defaultSettings from '@/settings'
import useSettingsStore from '@/store/modules/settings'

/**
 * 动态修改标题
 */
export function useDynamicTitle() {
  const settingsStore = useSettingsStore()
  const brand = settingsStore.platform.title && settingsStore.platform.title !== '跟随系统名称' ? settingsStore.platform.title : settingsStore.systemName
  if (settingsStore.dynamicTitle) {
    document.title = settingsStore.title + ' - ' + brand
  } else {
    document.title = brand || defaultSettings.title
  }
}