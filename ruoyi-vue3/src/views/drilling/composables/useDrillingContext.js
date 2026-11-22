import useUserStore from '@/store/modules/user'
import { checkPermi } from '@/utils/permission'
import { computed, reactive, ref } from 'vue'
import { listDrillingFields, getCurrentFieldConfig, listWells, listFieldFiles } from '@/api/drillingField'

// 工程上下文只使用井场作为业务范围。
const fields = ref([])
const fieldId = ref(Number(localStorage.getItem('drilling.fieldId')) || null)
// The confirmation gate is scoped to the engineering object currently being
// evaluated.  Keep the selected wellbore in the shared context so changing a
// wellbore can re-check once, while opening a project/file page cannot.
const activeWellboreId = ref(Number(localStorage.getItem('drilling.activeWellboreId')) || null)
const liveWellboreId = ref(null)
const currentConfig = ref(null)
const wells = ref([])
const fieldFiles = ref([])
const loading = ref(false)
let contextRequest = 0

import nativeDefaults from './native-defaults.json'
const defaults=()=>JSON.parse(JSON.stringify(nativeDefaults))

const configForm = reactive({
  coordinateSystem: 'LOCAL_ENU', northReference: 'GRID_NORTH', lengthUnit: 'm', angleUnit: 'deg', tvdPositive: 'D',
  originNorth: 0, originEast: 0, originTvd: 0, magneticDeclination: 0, meridianConvergence: 0, algorithmDefaults: defaults()
})

const currentFieldName = computed(() => fields.value.find(item => Number(item.field_id) === Number(fieldId.value))?.field_name || '')

function mergeDefaults(raw) {
  const base = defaults()
  let parsed = raw
  try { if (typeof raw === 'string') parsed = JSON.parse(raw) } catch (_) { parsed = null }
  if (!parsed) return base
  const model=parsed.errorModel?.settings ? parsed.errorModel : base.errorModel
  return {...base,...parsed, reference:{...base.reference,...parsed.reference}, thresholds:{...base.thresholds,...parsed.thresholds},errorModel:{...base.errorModel,...model},planning:{...base.planning,...parsed.planning,nativeOptions:{...base.planning.nativeOptions,...parsed.planning?.nativeOptions}},prediction:{...base.prediction,...parsed.prediction}}

}

async function loadFields(options = {}) {
  fields.value = await listDrillingFields() || []
  if (!fields.value.some(item => Number(item.field_id) === Number(fieldId.value))) fieldId.value = fields.value[0]?.field_id || null
  if (fieldId.value) await loadFieldContext(options)
  else { wells.value = []; currentConfig.value = null; fieldFiles.value = [] }
}

async function loadFieldContext(options = {}) {
  if (!fieldId.value) return
  const selectedFieldId = fieldId.value
  const requestId = ++contextRequest
  localStorage.setItem('drilling.fieldId', String(fieldId.value))
  loading.value = true
  try {
    const allowed=useUserStore().allowedModules
    const readFiles=allowed.some(key=>['files','models','surveys'].includes(key)) && checkPermi(['drilling:file:list'])
    const readWells=checkPermi(['drilling:well:list'])
    if (options.basicOnly) {
      const wellRows = readWells ? await listWells(selectedFieldId) : []
      if (requestId === contextRequest && selectedFieldId === fieldId.value) wells.value = wellRows || []
      return
    }
    const [config, wellRows, files, templateFiles] = await Promise.all([
      getCurrentFieldConfig(selectedFieldId), readWells ? listWells(selectedFieldId) : [],
      readFiles ? listFieldFiles(selectedFieldId) : [], readFiles ? listFieldFiles(selectedFieldId, { templates: true }) : []
    ])
    if (requestId !== contextRequest || selectedFieldId !== fieldId.value) return
    currentConfig.value = config
    wells.value = wellRows || []
    fieldFiles.value = [...(files || []), ...(templateFiles || [])]
    Object.assign(configForm, {
      coordinateSystem: config.coordinate_system, northReference: config.north_reference, lengthUnit: config.length_unit,
      angleUnit: config.angle_unit, tvdPositive: config.tvd_positive, originNorth: Number(config.origin_north || 0),
      originEast: Number(config.origin_east || 0), originTvd: Number(config.origin_tvd || 0),
      magneticDeclination: Number(config.magnetic_declination || 0), meridianConvergence: Number(config.meridian_convergence || 0),
      algorithmDefaults: mergeDefaults(config.algorithm_defaults_json || config.config_json)
    })
  } finally { if (requestId === contextRequest) loading.value = false }
}

function selectField(value, options = {}) {
  fieldId.value = value
  activeWellboreId.value = null
  liveWellboreId.value = null
  localStorage.removeItem('drilling.activeWellboreId')
  return loadFieldContext(options)
}

function errorModelPayload(model = configForm.algorithmDefaults.errorModel) {
  return { mode:model.mode, model_version:model.modelVersion, k:Number(model.k), sf_method:model.sfMethod, settings:model.settings, radii_margin_m:Number(model.radiiMarginM), sigma_pa_m:Number(model.sigmaPaM), curve_tolerance_m:Number(model.curveToleranceM) }
}

function thresholdsPayload() {
  const t = configForm.algorithmDefaults.thresholds
  return { red_sf: Number(t.redSf), orange_sf: Number(t.orangeSf), yellow_sf: Number(t.yellowSf), red_distance: Number(t.redDistance), error_net_ratio_orange: Number(t.errorNetRatioOrange), error_net_ratio_yellow: Number(t.errorNetRatioYellow) }
}

export function useDrillingContext() {
  const user = useUserStore()
  const canConfigure = computed(() => {
    const field = fields.value.find(item => Number(item.field_id) === Number(fieldId.value))
    return !!field && user.allowedModules.includes('config') && checkPermi(['drilling:field:config'])
      && (user.accountTier === 'ADMIN' || (user.accountTier === 'ENGINEER' && field.owner_user_id != null && String(field.owner_user_id) === String(user.id)))
  })
  return { fields, fieldId, canConfigure, activeWellboreId, liveWellboreId, currentConfig, wells, fieldFiles, loading, configForm, currentFieldName, defaults, loadFields, loadFieldContext, selectField, errorModelPayload, thresholdsPayload }
}
