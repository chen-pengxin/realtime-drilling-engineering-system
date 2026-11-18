<template>
  <el-alert v-if="error && !visible && eligible" :title="error" type="warning" :closable="false" class="confirmation-read-error"><el-button link type="primary" :loading="busy" @click="check">重新读取</el-button><el-button link type="primary" @click="configure">检查参数</el-button></el-alert>
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
import { computed, reactive, ref, toRefs, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import useUserStore from '@/store/modules/user'
import { registry } from '@/api/drillingRegistry'
import { errorModelName, errorParameterRows, separationFactorDetails } from '@/utils/engineeringErrorModels'
import { useDrillingContext } from '../composables/useDrillingContext'
import { engineConfirmationOpen } from '../composables/engineConfirmationState'
import { createEngineConfirmation, isEngineeringEvaluationRoute, readLoginSessionId, rotateLoginSessionId } from '@/utils/engineConfirmation'
const { fieldId, activeWellboreId, liveWellboreId, currentConfig } = useDrillingContext()
const route = useRoute(), router = useRouter(), user = useUserStore()
const liveRoute = computed(() => route.path === '/drilling/realtime' || route.path === '/drilling/basic/realtime-data')
const evaluationWellboreId = computed(() => liveRoute.value ? liveWellboreId.value : activeWellboreId.value)
const eligible = computed(() => !!user.token && isEngineeringEvaluationRoute(route.path) && !user.demoOnly && !user.mustChangePassword && user.permissions.some(p => ['*:*:*','drilling:collision:run','drilling:plan:run'].includes(p)))
const loginSessionId = ref(readLoginSessionId())
watch(() => user.token, (token, previous) => {
  if (token && token !== previous) loginSessionId.value = rotateLoginSessionId()
})
const state = reactive({ visible: false, snapshot: null, checked: false, busy: false, error: '', fieldId: null, wellboreId: null })
const { visible, snapshot, checked, busy, error } = toRefs(state)
watch(visible, value => { engineConfirmationOpen.value = value }, { flush: 'sync' })
const gate = createEngineConfirmation({
  state,
  context: () => ({ fieldId: fieldId.value, wellboreId: evaluationWellboreId.value, userId: user.id, sessionId: loginSessionId.value, configVersion: currentConfig.value?.config_version, eligible: eligible.value }),
  read: field => registry(field, '/engine-confirmation', 'get', undefined, undefined, { silentError: true }),
  submit: (field, configHash) => registry(field, '/engine-confirmation', 'post', { configHash }, undefined, { silentError: true }),
  onConfirmed: field => window.dispatchEvent(new CustomEvent('drilling-engine-confirmed', { detail: { fieldId: field } }))
})
const check = gate.check, confirm = gate.confirm
const openSections = ref(['parameters'])
const errorConfiguration = computed(() => snapshot.value?.configuration?.errorModel || {})
const uncertaintyModelName = computed(() => errorModelName(errorConfiguration.value.settings?.uncertainty_model?.type || errorConfiguration.value.modelVersion))
const sfDetails = computed(() => separationFactorDetails(errorConfiguration.value))
const activeParameters = computed(() => errorParameterRows(errorConfiguration.value))
const kParameter = computed(() => activeParameters.value.find(row => row.key === 'k'))
const parameterRows = computed(() => activeParameters.value.filter(row => row.key !== 'k'))
const versionProfiles = computed(() => (snapshot.value?.surveyProfiles||[]).filter(p=>p.parameter_source!=='FIELD_CONFIGURATION'||JSON.stringify(p.settings)!==JSON.stringify(errorConfiguration.value.settings)))
function configure() { gate.reset(); router.push('/drilling/basic/config?tab=errorModel') }
// One scoped watcher avoids duplicate reads from overlapping immediate
// watchers. Route tabs and survey-version events do not change this scope.
watch(() => [eligible.value, fieldId.value, evaluationWellboreId.value, currentConfig.value?.config_version, user.id, loginSessionId.value], ([isEligible, field, wellbore, version]) => {
  if (!isEligible) { gate.reset(); return }
  if (field && wellbore && version != null) check()
}, { immediate: true, flush: 'post' })
function required(event) {
  if (event.detail?.fieldId && Number(event.detail.fieldId) !== Number(fieldId.value)) return
  if (eligible.value) check({ force: true })
}
onMounted(() => window.addEventListener('drilling-engine-confirmation-required', required))
onBeforeUnmount(() => {
  gate.reset()
  window.removeEventListener('drilling-engine-confirmation-required', required)
})
</script>
<style scoped>
:global(.el-dialog.engine-confirmation-dialog.is-align-center){display:flex;flex-direction:column;max-height:calc(100dvh - 32px);margin:auto!important}:global(.engine-confirmation-dialog .el-dialog__body){min-height:0;overflow-y:auto}:global(.engine-confirmation-dialog .el-dialog__header),:global(.engine-confirmation-dialog .el-dialog__footer){flex-shrink:0}
.confirm-content{margin-top:14px;min-height:100px}.confirm-summary{margin:0 0 14px}.formula-card{border:1px solid var(--el-border-color-light);background:var(--el-fill-color-lighter);padding:12px 14px}.formula-card h4{margin:0 0 10px;font-size:14px;color:var(--el-text-color-primary)}.formula{font-family:Georgia,'Times New Roman',serif;font-size:18px;line-height:1.6;color:var(--el-text-color-primary);overflow-wrap:anywhere}.formula-card p{margin:8px 0 0;font-size:13px;line-height:1.7;color:var(--el-text-color-regular)}.parameter-collapse{margin-top:14px}.el-checkbox{height:auto;margin-top:16px;white-space:normal}.el-checkbox :deep(.el-checkbox__label){white-space:normal;line-height:1.6}
</style>
