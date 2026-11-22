import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import useUserStore from './user'
import { useDrillingContext } from '@/views/drilling/composables/useDrillingContext'
import { emptyTrajectoryStyles, normalizeTrajectoryStyles, resolveTrajectoryStyle, setTrajectoryStyle,normalizeGridStyle,resolveGridStyle } from '@/utils/trajectoryStyles'

export const trajectoryStyleStorageKey = userId => `drilling.trajectory-styles.v1.${encodeURIComponent(userId)}`

export default defineStore('trajectoryAppearance', () => {
  const user = useUserStore()
  const { currentConfig } = useDrillingContext()
  const engineeringStyles = computed(() => {
    try {
      const raw = currentConfig.value?.algorithm_defaults_json || currentConfig.value?.config_json
      const defaults = typeof raw === 'string' ? JSON.parse(raw) : raw
      return normalizeTrajectoryStyles(defaults?.visualization)
    } catch { return emptyTrajectoryStyles() }
  })
  const preferences = ref(emptyTrajectoryStyles())
  const storageError = ref(false)
  const storageKey = computed(() => user.token && user.id ? trajectoryStyleStorageKey(user.id) : '')
  function read() {
    storageError.value = false
    try {
      preferences.value = normalizeTrajectoryStyles(JSON.parse(storageKey.value ? localStorage.getItem(storageKey.value) : 'null'))
    } catch {
      preferences.value = emptyTrajectoryStyles()
    }
  }
  watch(storageKey, read, { immediate: true, flush: 'sync' })
  function save(value) {
    preferences.value = value
    storageError.value = false
    try {
      if (storageKey.value) localStorage.setItem(storageKey.value, JSON.stringify(value))
    } catch { storageError.value = true }
  }
  return {
    preferences, engineeringStyles, storageError,
    gridStyle:computed(()=>resolveGridStyle(preferences.value,engineeringStyles.value)),
    applyGrid:value=>save({...preferences.value,grid:normalizeGridStyle(value)}),
    styleFor: trajectory => resolveTrajectoryStyle(preferences.value, trajectory, engineeringStyles.value),
    apply: options => {
      if (options.reset) return save(setTrajectoryStyle(preferences.value, options))
      let next = preferences.value
      for (const id of options.wellboreIds?.length ? options.wellboreIds : [null]) {
        const inherited = resolveTrajectoryStyle({styles:id ? next.styles : {}}, {
          kind: options.role==='NEIGHBOR' ? 'NEIGHBOR' : options.type,
          trajectory_kind: options.type, wellbore_id: id
        }, engineeringStyles.value)
        // View adjustments store only differences. Unchanged parameters keep
        // following the published engineering configuration.
        const style=Object.fromEntries(Object.entries(options.style||{}).filter(([key,value])=>value!==inherited[key]))
        next=setTrajectoryStyle(next,{...options,wellboreIds:id?[id]:[],style})
      }
      save(next)
    },
    resetAll: () => save(emptyTrajectoryStyles())
  }
})
