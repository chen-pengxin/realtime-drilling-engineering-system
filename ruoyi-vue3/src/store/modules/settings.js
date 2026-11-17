import defaultSettings from '@/settings'
import { useDark, useToggle } from '@vueuse/core'
import { useDynamicTitle } from '@/utils/dynamicTitle'
import { handleThemeStyle } from '@/utils/theme'

// Start with the light RuoYi palette while keeping the manual theme switch.
// VueUse's useDark delegates to useColorMode, whose initialValue is a mode
// name ("dark", "light", or "auto"), not a boolean. A boolean here makes
// its DOM updater call `true.split(...)` during the immediate watcher and
// aborts application startup. Remove values written by the old boolean
// configuration before initializing the composable.
const themeStorageKey = 'drilling-console-theme'
const themeMigrationKey = 'drilling-light-theme-migrated'
if (!localStorage.getItem(themeMigrationKey)) {
  localStorage.removeItem(themeStorageKey)
  try {
    const saved = JSON.parse(localStorage.getItem('layout-setting') || '{}')
    if (saved.sideTheme === 'theme-dark') {
      saved.sideTheme = 'theme-light'
      localStorage.setItem('layout-setting', JSON.stringify(saved))
    }
  } catch (_) {
    localStorage.removeItem('layout-setting')
  }
  localStorage.setItem(themeMigrationKey, '1')
}
const storedTheme = localStorage.getItem(themeStorageKey)
if (storedTheme && !['dark', 'light', 'auto'].includes(storedTheme.replace(/^"|"$/g, ''))) {
  localStorage.removeItem(themeStorageKey)
}
const isDark = useDark({ storageKey: themeStorageKey, initialValue: 'light' })
const toggleDark = useToggle(isDark)

const { sideTheme, showSettings, navType, tagsView, tagsViewPersist, tagsIcon, tagsViewStyle, fixedHeader, sidebarLogo, dynamicTitle, footerVisible, footerContent } = defaultSettings

const storageSetting = JSON.parse(localStorage.getItem('layout-setting')) || ''

const useSettingsStore = defineStore(
  'settings',
  {
    state: () => ({
      title: '',
      systemName: defaultSettings.title,
      theme: storageSetting.theme || '#409EFF',
      sideTheme: storageSetting.sideTheme || sideTheme,
      showSettings: showSettings,
      navType: storageSetting.navType === undefined ? navType : storageSetting.navType,
      tagsView: storageSetting.tagsView === undefined ? tagsView : storageSetting.tagsView,
      tagsViewPersist: storageSetting.tagsViewPersist === undefined ? tagsViewPersist : storageSetting.tagsViewPersist,
      tagsIcon: storageSetting.tagsIcon === undefined ? tagsIcon : storageSetting.tagsIcon,
      tagsViewStyle: storageSetting.tagsViewStyle === undefined ? tagsViewStyle : storageSetting.tagsViewStyle,
      fixedHeader: storageSetting.fixedHeader === undefined ? fixedHeader : storageSetting.fixedHeader,
      sidebarLogo: storageSetting.sidebarLogo === undefined ? sidebarLogo : storageSetting.sidebarLogo,
      dynamicTitle: storageSetting.dynamicTitle === undefined ? dynamicTitle : storageSetting.dynamicTitle,
      footerVisible: storageSetting.footerVisible === undefined ? footerVisible : storageSetting.footerVisible,
      footerContent: footerContent,
      isDark: isDark.value
    }),
    actions: {
      // 修改布局设置
      changeSetting(data) {
        const { key, value } = data
        if (this.hasOwnProperty(key)) {
          this[key] = value
        }
      },
      // 设置网页标题
      setTitle(title) {
        this.title = title
        useDynamicTitle()
      },
      // 切换暗黑模式
      toggleTheme() {
        this.isDark = !this.isDark
        toggleDark()
        nextTick(() => {
          handleThemeStyle(this.theme)
        })
      }
    }
  })

export default useSettingsStore
