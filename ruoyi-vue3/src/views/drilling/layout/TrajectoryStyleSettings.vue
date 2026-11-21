<template>
  <el-form class="trajectory-style-settings" label-position="top" size="small" @submit.prevent="apply(false)">
    <el-form-item label="井眼角色"><el-select v-model="role" aria-label="井眼角色"><el-option v-for="(label,key) in trajectoryRoles" :key="key" :label="label" :value="key" /></el-select></el-form-item>
    <el-form-item label="轨迹类型"><el-select v-model="type" aria-label="轨迹类型"><el-option v-for="(label,key) in trajectoryTypes" :key="key" :label="label" :value="key" /></el-select></el-form-item>
    <el-form-item label="应用范围"><el-radio-group v-model="scope" aria-label="样式应用范围"><el-radio value="all">同类轨迹</el-radio><el-radio value="selected">指定井眼</el-radio></el-radio-group></el-form-item>
    <el-form-item v-if="scope==='selected'" label="井眼"><WellboreSelect v-model="wellboreIds" :rows="rows" multiple /></el-form-item>
    <el-form-item label="颜色"><div class="color-field"><el-color-picker v-model="draft.color" :predefine="colors" aria-label="轨迹颜色" @change="engineeringChanged" /><el-input v-model="draft.color" maxlength="7" aria-label="轨迹颜色值" @change="engineeringChanged" /></div></el-form-item>
    <el-form-item label="线宽（px）"><el-input-number v-model="draft.width" :min="0.5" :max="8" :step="0.25" :precision="2" controls-position="right" aria-label="轨迹线宽" @change="engineeringChanged" /></el-form-item>
    <el-form-item label="线型"><el-select v-model="draft.dash" aria-label="轨迹线型" @change="engineeringChanged"><el-option v-for="(label,key) in trajectoryLineTypes" :key="key" :label="label" :value="key" /></el-select></el-form-item>
    <el-form-item label="不透明度（%）"><el-input-number v-model="opacity" :min="10" :max="100" :step="5" controls-position="right" aria-label="轨迹不透明度" @change="engineeringChanged" /></el-form-item>
    <el-form-item v-if="type==='DESIGN'||type==='PLANNED'" label="设计节点大小（px）"><el-input-number v-model="draft.nodeSize" :min="2" :max="12" :step="1" controls-position="right" aria-label="设计节点大小" @change="engineeringChanged" /></el-form-item>
    <div class="style-preview" aria-label="轨迹样式预览"><svg viewBox="0 0 180 30" role="img"><path d="M 10 23 L 70 8 L 120 17 L 170 5" fill="none" :stroke="draft.color" :stroke-width="draft.width" :stroke-dasharray="dashArray" :opacity="draft.opacity" /></svg></div>
    <div class="style-actions"><el-button type="primary" :disabled="scope==='selected'&&!wellboreIds.length" @click="apply(false)">{{engineering?'更新样式':'应用样式'}}</el-button><el-button :disabled="scope==='selected'&&!wellboreIds.length" @click="apply(true)">{{scope==='selected'?'恢复同类样式':engineering?'恢复默认':'恢复工程配置'}}</el-button></div>
    <p class="style-note">{{engineering?'用于全系统的二维、三维轨迹，保存新版本后生效。':'以工程统一参数为基础调整显示，当前账号的调整保存在本浏览器。'}}指定井眼设置优先，风险井段按评价等级着色。</p>
    <router-link v-if="!engineering" to="/drilling/basic/config?tab=visualization" class="engineering-link">工程统一参数</router-link>
    <el-button link type="primary" @click="resetAll">{{engineering?'重置全部样式':'清除全部视图调整'}}</el-button>
  </el-form>
</template>
<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import WellboreSelect from './WellboreSelect.vue'
import useTrajectoryAppearance from '@/store/modules/trajectoryAppearance'
import { trajectoryRoles, trajectoryTypes, trajectoryLineTypes, trajectoryCategory, resolveTrajectoryStyle, setTrajectoryStyle, emptyTrajectoryStyles } from '@/utils/trajectoryStyles'

const props = defineProps({ trajectories: { type: Array, default: () => [] }, engineering:Boolean, modelValue:Object })
const emit = defineEmits(['update:modelValue'])
const appearance = useTrajectoryAppearance()
const role = ref('REFERENCE'), type = ref('ACTUAL'), scope = ref('all'), wellboreIds = ref([])
const draft = reactive({})
const colors = ['#2563eb','#7c3aed','#16a34a','#000000','#9ca3af','#b9a8d0','#0891b2','#475569']
const rows = computed(() => [...new Map(props.trajectories.filter(t => (props.engineering || trajectoryCategory(t).role === role.value) && Number(t.wellbore_id)>0).map(t => [Number(t.wellbore_id),t])).values()])
const opacity = computed({get:()=>Math.round(draft.opacity*100),set:value=>{draft.opacity=value/100}})
const dashArray = computed(() => ({solid:'',dash:'8 5',dot:'2 4',dashdot:'8 4 2 4'}[draft.dash]))
function loadDraft() {
  const trajectory={kind:role.value==='NEIGHBOR'?'NEIGHBOR':type.value,trajectory_kind:type.value,wellbore_id:scope.value==='selected'?wellboreIds.value[0]:undefined}
  Object.assign(draft, props.engineering?resolveTrajectoryStyle(props.modelValue,trajectory):appearance.styleFor(trajectory))
}
watch([role, rows], () => { wellboreIds.value=wellboreIds.value.filter(id=>rows.value.some(t=>Number(t.wellbore_id)===id)) })
watch([role,type,scope,wellboreIds,()=>appearance.preferences,()=>appearance.engineeringStyles,()=>props.modelValue], loadDraft, {immediate:true,deep:true})
function engineeringChanged() {
  if(!props.engineering || scope.value==='selected'&&!wellboreIds.value.length || !/^#[0-9a-f]{6}$/i.test(draft.color||''))return
  emit('update:modelValue',setTrajectoryStyle(props.modelValue,{role:role.value,type:type.value,wellboreIds:scope.value==='selected'?wellboreIds.value:[],style:draft}))
}
function apply(reset) {
  if(scope.value==='selected'&&!wellboreIds.value.length)return
  if(!reset&&!/^#[0-9a-f]{6}$/i.test(draft.color||'')){ElMessage.warning('请输入六位十六进制颜色，例如 #075985');return}
  const options={role:role.value,type:type.value,wellboreIds:scope.value==='selected'?wellboreIds.value:[],style:draft,reset}
  if(props.engineering){emit('update:modelValue',setTrajectoryStyle(props.modelValue,options));ElMessage.success('样式已更新，请保存工程参数新版本');return}
  appearance.apply(options)
  if(appearance.storageError)ElMessage.warning('样式已应用，浏览器未能保存设置')
  else ElMessage.success(reset?'已恢复轨迹样式':'轨迹样式已保存')
  loadDraft()
}
async function resetAll() {
  try { await ElMessageBox.confirm(props.engineering?'恢复所有轨迹的默认样式？':'清除全部视图调整并恢复工程统一配置？','重置轨迹样式',{type:'warning',confirmButtonText:'重置',cancelButtonText:'取消'}) } catch { return }
  if(props.engineering){emit('update:modelValue',emptyTrajectoryStyles());return}
  appearance.resetAll(); loadDraft()
  if(appearance.storageError)ElMessage.warning('样式已恢复，浏览器未能保存设置')
}
</script>
<style scoped>
.trajectory-style-settings{min-width:0}.engineering-link{display:block;font-size:12px;color:var(--el-color-primary);margin-bottom:10px}.trajectory-style-settings :deep(.el-select),.trajectory-style-settings :deep(.el-input-number){width:100%}.color-field{display:flex;gap:8px;min-width:0;width:100%}.color-field .el-input{min-width:0}.style-actions{display:flex;flex-wrap:wrap;gap:8px}.style-actions .el-button{margin:0}.style-preview{border:1px solid var(--el-border-color-light);background:var(--el-bg-color);margin-bottom:12px;height:38px}.style-preview svg{width:100%;height:100%}.style-note{font-size:12px;line-height:1.6;color:var(--el-text-color-regular)}
</style>
