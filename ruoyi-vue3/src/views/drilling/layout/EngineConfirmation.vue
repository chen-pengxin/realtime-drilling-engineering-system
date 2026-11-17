<template>
  <el-dialog v-model="visible" title="确认工程评价配置" class="engine-confirmation-dialog" width="min(820px, calc(100vw - 24px))" align-center append-to-body :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
    <el-alert title="请核对轨道不确定性模型、实际使用参数及分离系数公式" type="warning" :closable="false" />
    <div v-loading="busy" class="confirm-content">
      <template v-if="snapshot">
        <el-descriptions :column="1" border class="confirm-summary">
          <el-descriptions-item v-if="kParameter" :label="kParameter.label">{{ kParameter.value }}</el-descriptions-item>
          <el-descriptions-item label="轨道不确定性模型">{{ uncertaintyModelName }}</el-descriptions-item>
        </el-descriptions>
        <section class="formula-card">
          <h4>分离系数计算公式</h4>
          <div class="formula">{{ sfDetails.formula }}</div>
          <p>{{ sfDetails.definition }}</p>
        </section>
        <el-collapse v-model="openSections" class="parameter-collapse">
          <el-collapse-item title="计算参数" name="parameters">
            <el-table :data="parameterRows" border size="small" max-height="330">
              <el-table-column prop="label" label="工程参数" min-width="255" />
              <el-table-column prop="value" label="当前值" min-width="150" />
              <el-table-column prop="unit" label="单位" min-width="90" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-collapse v-if="versionProfiles.length" class="parameter-collapse">
          <el-collapse-item v-for="(profile,index) in versionProfiles" :key="index" :title="`${profile.tool?.name||'历史版本（井场参数）'} · ${errorModelName(profile.settings?.uncertainty_model?.type||profile.model_version)}`" :name="index">
            <el-table :data="errorParameterRows({settings:profile.settings,modelVersion:profile.model_version})" border size="small" max-height="270"><el-table-column prop="label" label="版本测量参数" min-width="255"/><el-table-column prop="value" label="当前值" min-width="150"/><el-table-column prop="unit" label="单位" width="90"/></el-table>
          </el-collapse-item>
        </el-collapse>
      </template>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-checkbox v-model="checked" :disabled="busy || !snapshot">我已核对轨道不确定性模型、实际使用参数及分离系数公式</el-checkbox>
    <template #footer>
      <el-button @click="configure">检查/修改参数</el-button>
      <el-button v-if="error" @click="check">重试</el-button>
      <el-button type="primary" :loading="busy" :disabled="!checked || !snapshot || !!error" @click="confirm">确认配置</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import useUserStore from '@/store/modules/user'
import { registry } from '@/api/drillingRegistry'
import { errorModelName, errorParameterRows, separationFactorDetails } from '@/utils/engineeringErrorModels'
import { useDrillingContext } from '../composables/useDrillingContext'
import { engineConfirmationOpen } from '../composables/engineConfirmationState'
const { fieldId, currentConfig, currentFieldName: fieldName } = useDrillingContext()
const route = useRoute(), router = useRouter(), user = useUserStore()
const eligible = computed(() => route.path.startsWith('/drilling') && !route.path.includes('/basic/config') && !user.demoOnly && !user.mustChangePassword && user.permissions.some(p => ['*:*:*','drilling:collision:run','drilling:plan:run','drilling:prediction:run'].includes(p)))
const visible = engineConfirmationOpen, snapshot = ref(), checked = ref(false), busy = ref(false), error = ref('')
const openSections = ref(['parameters'])
const errorConfiguration = computed(() => snapshot.value?.configuration?.errorModel || {})
const uncertaintyModelName = computed(() => errorModelName(errorConfiguration.value.settings?.uncertainty_model?.type || errorConfiguration.value.modelVersion))
const sfDetails = computed(() => separationFactorDetails(errorConfiguration.value))
const activeParameters = computed(() => errorParameterRows(errorConfiguration.value))
const kParameter = computed(() => activeParameters.value.find(row => row.key === 'k'))
const parameterRows = computed(() => activeParameters.value.filter(row => row.key !== 'k'))
const versionProfiles = computed(() => (snapshot.value?.surveyProfiles||[]).filter(p=>p.parameter_source!=='FIELD_CONFIGURATION'||JSON.stringify(p.settings)!==JSON.stringify(errorConfiguration.value.settings)))
let requestSerial = 0
let pending, pendingField, snapshotField
async function check() {
  const field = fieldId.value
  if (!field || !eligible.value) { requestSerial++; pending = null; visible.value = false; busy.value = false; return }
  const key=`${field}:${currentConfig.value?.config_version}`
  if (pending && pendingField === key) return pending
  if (snapshotField !== field) { visible.value = false; snapshot.value = undefined; checked.value = false }
  const request = ++requestSerial
  pendingField = key
  busy.value = true
  const operation = (async () => {
    try {
      const value = await registry(field, '/engine-confirmation')
      if (request !== requestSerial || field !== fieldId.value || !eligible.value) return
      if (snapshot.value?.configHash !== value.configHash || snapshot.value?.sessionHash !== value.sessionHash) checked.value = false
      snapshot.value = value
      snapshotField = field
      error.value = ''
      // Do not display a dialog until the server actually requires review.
      visible.value = !value.confirmed
    } catch (e) {
      if (request === requestSerial) { checked.value = false; snapshot.value = undefined; error.value = e.message || '无法读取工程评价参数'; visible.value = true }
    } finally { if (request === requestSerial) busy.value = false }
  })()
  pending = operation
  try { await operation } finally { if (pending === operation) pending = null }
}
async function confirm() {
  if (!snapshot.value || !checked.value || busy.value) return
  const field = fieldId.value, value = snapshot.value, request = requestSerial
  busy.value = true; error.value = ''
  try {
    await registry(field, '/engine-confirmation', 'post', { configHash: value.configHash })
    if (field === fieldId.value && value === snapshot.value) {
      snapshot.value = { ...value, confirmed: true }
      visible.value = false
      window.dispatchEvent(new CustomEvent('drilling-engine-confirmed', { detail: { fieldId: field } }))
    }
  } catch (e) { if (request === requestSerial && field === fieldId.value) error.value = e.message || '参数确认失败，请重新核对' }
  finally { if (request === requestSerial) busy.value = false }
}
function configure() { visible.value = false; router.push('/drilling/basic/config?tab=errorModel') }
watch(() => [fieldId.value, currentConfig.value?.config_version, eligible.value], check, { immediate: true })
function required(event) {
  if (event.detail?.fieldId && Number(event.detail.fieldId) !== Number(fieldId.value)) return
  if (eligible.value && !busy.value) check()
}
const updateEvents=['drilling-engine-confirmation-required','drilling-survey-updated','drilling-survey-version-changed']
onMounted(() => updateEvents.forEach(name=>window.addEventListener(name, required)))
onBeforeUnmount(() => { requestSerial++; visible.value = false; updateEvents.forEach(name=>window.removeEventListener(name, required)) })
</script>
<style scoped>
:global(.el-dialog.engine-confirmation-dialog.is-align-center){display:flex;flex-direction:column;max-height:calc(100dvh - 32px);margin:auto!important}:global(.engine-confirmation-dialog .el-dialog__body){min-height:0;overflow-y:auto}:global(.engine-confirmation-dialog .el-dialog__header),:global(.engine-confirmation-dialog .el-dialog__footer){flex-shrink:0}
.confirm-content{margin-top:14px;min-height:100px}.confirm-summary{margin:0 0 14px}.formula-card{border:1px solid var(--el-border-color-light);background:var(--el-fill-color-lighter);padding:12px 14px}.formula-card h4{margin:0 0 10px;font-size:14px;color:var(--el-text-color-primary)}.formula{font-family:Georgia,'Times New Roman',serif;font-size:18px;line-height:1.6;color:var(--el-text-color-primary);overflow-wrap:anywhere}.formula-card p{margin:8px 0 0;font-size:13px;line-height:1.7;color:var(--el-text-color-regular)}.parameter-collapse{margin-top:14px}.el-checkbox{height:auto;margin-top:16px;white-space:normal}.el-checkbox :deep(.el-checkbox__label){white-space:normal;line-height:1.6}
</style>
