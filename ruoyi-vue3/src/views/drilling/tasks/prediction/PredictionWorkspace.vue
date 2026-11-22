<template>
  <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="pose-error" />
  <div class="pose-workspace">
    <aside class="pose-panel">
      <header class="pose-panel-header"><h3>预测输入</h3></header>
      <div class="pose-panel-body">
        <el-form label-position="top">
          <el-form-item label="当前井眼"><WellboreSelect v-model="boreId" :rows="bores" @change="changeWell" /></el-form-item>
          <el-form-item label="测斜数据版本"><el-select v-model="versionId" @change="changeVersion"><el-option v-for="version in versions" :key="version.survey_version_id" :value="version.survey_version_id" :label="`v${version.version_no} · MD ${formatNumber(version.max_md, 1)}`" /></el-select></el-form-item>
          <el-form-item label="向前预测距离（m）"><el-input-number v-model="form.step" :min="0.1" :max="1000" :step="1" @change="clearPrediction" /></el-form-item>
          <el-form-item label="推理设备"><el-select v-model="form.device" @change="clearPrediction"><el-option label="自动选择" value="auto"/><el-option label="CPU" value="cpu"/><el-option label="GPU 0" value="cuda:0"/></el-select></el-form-item>
          <el-form-item label="实时跟随"><el-switch v-model="autoFollow" active-text="跟随最新测斜版本" /></el-form-item>
          <el-button type="primary" :loading="busy" :disabled="!latest || !modelReady" style="width:100%" v-if="canRun" @click="runPrediction">运行轨迹预测</el-button>
          <el-button :disabled="busy" style="width:100%;margin:10px 0 0" @click="refresh">刷新实测数据</el-button>
        </el-form>
        <el-divider />
        <div class="pose-title-small">显示图层</div>
        <div class="pose-display-options"><el-checkbox v-model="showMeasured">实测井眼轨迹</el-checkbox><el-checkbox v-model="showPredicted">预测轨迹</el-checkbox></div>
        <el-divider />

        <router-link v-if="user.allowedModules.includes('models')" :to="{ name:'DrillingPredictionModels' }"><el-button link type="primary">模型管理</el-button></router-link>
      </div>
    </aside>
    <section>
      <el-descriptions class="prediction-summary" :column="2" border>
        <el-descriptions-item label="当前测深">{{ formatNumber(latest?.md,1) }} m</el-descriptions-item>
        <el-descriptions-item label="当前井斜">{{ formatNumber(latest?.inclination) }} °</el-descriptions-item>
        <el-descriptions-item label="当前方位">{{ formatNumber(latest?.azimuth) }} °</el-descriptions-item>
        <el-descriptions-item label="预测终点 MD">{{ formatNumber(predicted.at(-1)?.md,1) }} m</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="!modelReady" title="MODEL_NOT_CONFIGURED · 未生成预测结果" type="info" :closable="false" class="pose-error">请先在模型仓库启用模型。</el-alert>
      <section class="pose-panel pose-view">
        <div class="pose-panel-header"><h3>预测结果</h3><el-button :disabled="!predicted.length" @click="exportResults">导出预测 CSV</el-button></div>
        <div class="pose-legend"><span><i style="background:#075985"></i>实测轨迹</span><span><i style="background:#9a3412"></i>模型预测</span><span>坐标 N / E / TVD · m</span></div>
        <el-tabs v-model="activeView">
          <el-tab-pane label="3D 轨迹" name="3d"><div v-if="activeView==='3d'" class="pose-scene"><TrajectoryPlot v-if="trajectories.length" :trajectories="trajectories" view="3d"/><el-empty v-else description="暂无井眼轨迹" class="pose-empty" /></div></el-tab-pane>
          <el-tab-pane label="2D 投影" name="2d"><PredictionPlots v-if="activeView==='2d' && measured.length" :measured="showMeasured ? measured : []" :predicted="showPredicted ? predicted : []"/><div v-else-if="activeView==='2d'" class="pose-empty">暂无测斜数据</div></el-tab-pane>
          <el-tab-pane label="姿态趋势" name="attitude"><PredictionPlots v-if="activeView==='attitude' && measured.length" :measured="measured" :predicted="predicted" attitude/><div v-else-if="activeView==='attitude'" class="pose-empty">暂无姿态数据</div></el-tab-pane>
          <el-tab-pane label="预测轨迹表" name="table"><div class="pose-table"><el-table empty-text="未生成预测结果" :data="predicted" max-height="520"><el-table-column v-for="column in columns" :key="column.key" :label="column.label" min-width="100"><template #default="{row}">{{ formatNumber(row[column.key]) }}</template></el-table-column></el-table></div></el-tab-pane>
          <el-tab-pane label="预测历史" name="history"><div class="pose-table"><el-table :data="history" max-height="520"><el-table-column prop="create_time" label="时间" min-width="160"/><el-table-column prop="model_version" label="模型版本" width="100"/><el-table-column prop="prediction_step" label="预测距离（m）" width="130"/><el-table-column prop="status" label="状态" min-width="150"/><el-table-column label="结果" width="80"><template #default="{row}"><el-button link type="primary" @click="showHistory(row)">查看</el-button></template></el-table-column></el-table></div></el-tab-pane>
        </el-tabs>
        <div class="pose-result-strip"><div><small>当前姿态 · 井斜 / 方位</small><b>{{task?.result?.zero_length_pose?`${formatNumber(task.result.zero_length_pose.inclination)}° / ${formatNumber(task.result.zero_length_pose.azimuth)}°`:'— / —'}}</b></div><div><small>前方 9 m · 井斜 / 方位</small><b>{{ attitudeAt(9) }}</b></div><div><small>前方 28 m · 井斜 / 方位</small><b>{{ attitudeAt(28) }}</b></div><div><small>预测来源</small><b>{{ predicted.length ? '训练模型' : '等待模型结果' }}</b><em>{{ task?.created_at || '未运行' }}</em></div></div>
      </section>
    </section>
    <aside class="pose-context">
      <section class="pose-panel"><header class="pose-panel-header"><h3>当前模型</h3><el-tag :type="modelReady?'success':'info'">{{ modelReady?'已启用':'待配置' }}</el-tag></header><div class="pose-panel-body"><dl class="pose-facts"><div><dt>模型</dt><dd>{{ activeModel?.model_name || '—' }}</dd></div><div><dt>版本</dt><dd>{{ activeModel ? 'v'+activeModel.version_no : '—' }}</dd></div><div><dt>推理设备</dt><dd>{{ form.device }}</dd></div><div><dt>预测步长</dt><dd>{{ form.step }} m</dd></div></dl></div></section>
      <section class="pose-panel"><header class="pose-panel-header"><h3>位置与数据来源</h3></header><div class="pose-panel-body"><dl class="pose-facts"><div v-for="axis in ['north','east','tvd']" :key="axis"><dt>{{ axis==='north'?'N':axis==='east'?'E':'TVD' }}</dt><dd>{{ formatNumber(latest?.[axis]) }} m</dd></div><div><dt>测斜版本</dt><dd>{{ currentVersion ? 'v'+currentVersion.version_no : '—' }}</dd></div><div><dt>数据源</dt><dd>{{ currentVersion?.source_format || '实测测斜' }}</dd></div></dl></div></section>
      <section class="pose-panel"><header class="pose-panel-header"><h3>模型评估与导向结果</h3></header><div class="pose-panel-body"><dl class="pose-facts"><div><dt>工具面（°）</dt><dd>{{formatNumber(task?.result?.toolface_deg)}}</dd></div><div><dt>滑动 / 复合比例</dt><dd>{{formatNumber(task?.result?.sliding_ratio)}} / {{formatNumber(task?.result?.composite_ratio)}}</dd></div><div><dt>评估指标</dt><dd>{{task?.result?.metrics?JSON.stringify(task.result.metrics):'—'}}</dd></div></dl></div></section>
      <section class="pose-panel"><header class="pose-panel-header"><h3>自动增量更新</h3><el-tag :type="learning?.policy?.enabled?'success':'info'">{{ learning?.policy?.enabled?'已开启':'未开启' }}</el-tag></header><div class="pose-panel-body"><dl class="pose-facts"><div><dt>状态</dt><dd>{{ learningStatus(learning?.state?.status) }}</dd></div><div><dt>新增样本</dt><dd>{{ learning?.state?.newSamples ?? 0 }} / {{ learning?.policy?.minimumNewSamples ?? 10 }}</dd></div><div><dt>已训练至</dt><dd>{{ formatNumber(learning?.state?.trainedThroughMd,1) }} m</dd></div></dl><p v-if="learning?.state?.error" class="pose-muted">{{ learning.state.error }}</p></div></section>
    </aside>
  </div>
  <footer class="pose-bottom"><span>当前井 <b>{{ currentWell?.well_code || '—' }}</b></span><span>实测站点 <b>{{ measured.length }}</b> · 预测站点 <b>{{ predicted.length }}</b></span><span>数据刷新 <b>{{ updatedAt }}</b></span></footer>
</template>
<script setup>
import { checkPermi } from '@/utils/permission'
import useUserStore from '@/store/modules/user'
import WellboreSelect from '@/views/drilling/layout/WellboreSelect.vue'
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { exportPredictionTrajectory, listModels, listPredictionTasks, startPrediction } from '@/api/drilling'
import { listWellbores,boreVersions,trajectoryVersion } from '@/api/drillingRegistry'
import TrajectoryPlot from '@/components/TrajectoryPlot'
import PredictionPlots from './PredictionPlots.vue'
import { useDrillingContext } from '../../composables/useDrillingContext'
import { decode, downloadBlob, formatNumber, getIncrementalLearning, learningStatus } from './api'
import {trajectoryColors} from '@/utils/trajectoryColors'
const { fieldId, wells, configForm, loadFields, loadFieldContext } = useDrillingContext()
const user = useUserStore()
const canRun = computed(() => checkPermi(['drilling:prediction:run']))
const canReadModels = computed(() => user.allowedModules.includes('models') && checkPermi(['drilling:model:list']))
const bores=ref([]),boreId=ref(null),measuredPlot=ref([]),wellId=ref(null), versionId=ref(null), versions=ref([]), measured=ref([]), task=ref(null), models=ref([]), history=ref([]), learning=ref(null)
const boreLabel=b=>[b?.block_name,b?.field_name,b?.well_name||b?.well_code,b?.wellbore_name||b?.wellbore_code].filter(Boolean).join(' / ')
const busy=ref(false), error=ref(''), autoFollow=ref(true), showMeasured=ref(true), showPredicted=ref(true), activeView=ref('3d'), updatedAt=ref('—')
const form=reactive({step:9,device:'auto'})
let generation=0, refreshGeneration=0, timer, mounted=false, refreshing=false, following=false, lastAutomaticInput=''
const currentWell=computed(()=>wells.value.find(row=>Number(row.well_id)===Number(wellId.value)))
const currentVersion=computed(()=>versions.value.find(row=>Number(row.survey_version_id)===Number(versionId.value)))
const latest=computed(()=>measured.value.at(-1))
const activeModel=computed(()=>models.value.find(row=>Number(row.model_version_id)===Number(configForm.algorithmDefaults.prediction.modelVersionId)))
const modelReady=computed(()=>Boolean((canReadModels.value ? activeModel.value : configForm.algorithmDefaults.prediction.modelVersionId) && configForm.algorithmDefaults.prediction.templateRuntimePath))
const predicted=computed(()=>{const result=task.value?.result;return result?.predicted_stations || (result?.predicted_pose?[result.predicted_pose]:[])})
const trajectories=computed(()=>{const lines=[];if(showMeasured.value&&measured.value.length>1)lines.push({id:'measured',kind:'ACTUAL',label:'实测井眼轨迹',color:trajectoryColors.ACTUAL,points:measuredPlot.value.length?measuredPlot.value:measured.value});if(showPredicted.value&&predicted.value.length)lines.push({id:'predicted',kind:'PREDICTED',label:'模型预测轨迹',color:trajectoryColors.PREDICTED,points:[...measured.value.slice(-1),...predicted.value]});return lines})
const columns=[{key:'md',label:'MD（m）'},{key:'inclination',label:'井斜（°）'},{key:'azimuth',label:'方位（°）'},{key:'north',label:'N（m）'},{key:'east',label:'E（m）'},{key:'tvd',label:'TVD（m）'}]
function clearPrediction() {
  generation++
  task.value=null
  error.value=''
  lastAutomaticInput=''
}
function attitudeAt(step) {
  const row=predicted.value.find(row=>Math.abs(Number(row.md)-Number(latest.value?.md)-step)<0.01)
  return row?`${formatNumber(row.inclination)}° / ${formatNumber(row.azimuth)}°`:'— / —'
}
async function loadHistory() {
  const id=fieldId.value, well=wellId.value
  const rows=await listPredictionTasks(id)||[]
  if(id===fieldId.value && well===wellId.value) history.value=rows.filter(row=>Number(row.well_id)===Number(well)&&versions.value.some(v=>Number(v.survey_version_id)===Number(row.survey_version_id)))
}
async function changeWell() {
  clearPrediction()
  const run=generation, id=fieldId.value, well=wellId.value
  measured.value=[]; versions.value=[]; history.value=[]; versionId.value=null
  const selected=bores.value.find(b=>Number(b.wellbore_id)===Number(boreId.value))
  if(!selected)return
  wellId.value=selected.well_id
  const rows=await boreVersions(id,selected.wellbore_id,'ACTUAL')||[]
  if(run!==generation) return
  versions.value=rows
  versionId.value=(rows.find(row=>Number(row.is_active)===1)||rows[0])?.survey_version_id||null
  await changeVersion()
  await loadHistory()
  if(autoFollow.value) await runAutomaticPrediction()
}
async function changeVersion() {
  clearPrediction()
  const run=generation
  measured.value=[]
  if(!versionId.value) return
  const detail=await trajectoryVersion(fieldId.value,versionId.value)
  if(run!==generation) return
  measuredPlot.value=detail.plot_stations||[]
  measured.value=[...(detail.stations||[])].sort((a,b)=>Number(a.md)-Number(b.md))
  updatedAt.value=new Date().toLocaleTimeString('zh-CN')
}
async function runPrediction() {
  if(!canRun.value||busy.value||!latest.value||!modelReady.value) return
  const run=generation
  busy.value=true; error.value=''
  try {
    const result=await startPrediction({field_id:fieldId.value,well_id:wellId.value,survey_version_id:versionId.value,prediction_step:form.step,device:form.device,input_summary:{source:'prediction-workspace'}})
    if(run!==generation) return
    task.value=result
    if(result.status!=='SUCCEEDED') error.value=result.result?.message||'模型未返回预测结果'
    await loadHistory()
  } catch(e) {
    if(run===generation) error.value=e.response?.data?.detail||e.message||'预测失败，请检查模型适配器'
  } finally { busy.value=false }
}
async function runAutomaticPrediction() {
  if(!canRun.value||busy.value||!latest.value||!modelReady.value) return
  const signature=[fieldId.value,wellId.value,versionId.value,configForm.algorithmDefaults.prediction.modelVersionId,configForm.algorithmDefaults.prediction.templateRuntimePath,form.step,form.device].join(':')
  if(signature===lastAutomaticInput) return
  lastAutomaticInput=signature
  await runPrediction()
}
async function refresh() {
  if(!fieldId.value) return
  const request=++refreshGeneration, id=fieldId.value
  refreshing=true
  try {
    await loadFieldContext()
    const [modelRows,learningData]=await Promise.all([canReadModels.value ? listModels(id) : {},canReadModels.value ? getIncrementalLearning(id) : null])
    if(request!==refreshGeneration||id!==fieldId.value) return
    models.value=modelRows.models||[]; learning.value=learningData
    bores.value=await listWellbores(id)
    if(!bores.value.some(b=>Number(b.wellbore_id)===Number(boreId.value)))boreId.value=bores.value.find(b=>b.engineering_status==='DRILLING'&&b.actual_version_id)?.wellbore_id||bores.value.find(b=>b.actual_version_id)?.wellbore_id||null
    await changeWell()
  } catch(e) { if(request===refreshGeneration) error.value=e.message||'加载预测工作区失败' }
  finally { if(request===refreshGeneration) refreshing=false }
}
async function follow() {
  if(!autoFollow.value||busy.value||refreshing||following||!wellId.value) return
  following=true
  const id=fieldId.value, well=wellId.value, run=generation
  const modelBefore=configForm.algorithmDefaults.prediction.modelVersionId
  const scriptBefore=configForm.algorithmDefaults.prediction.templateRuntimePath
  try {
    const [rows,learningData,modelRows]=await Promise.all([boreVersions(id,boreId.value,'ACTUAL'),canReadModels.value ? getIncrementalLearning(id) : null,canReadModels.value ? listModels(id) : {}])
    await loadFieldContext()
    if(id!==fieldId.value||well!==wellId.value||run!==generation) return
    learning.value=learningData; models.value=modelRows.models||[]
    const current=rows.find(row=>Number(row.is_active)===1)||rows[0]
    if(current&&Number(current.survey_version_id)!==Number(versionId.value)) {
      versions.value=rows; versionId.value=current.survey_version_id
      await changeVersion()
    } else if(modelBefore!==configForm.algorithmDefaults.prediction.modelVersionId||scriptBefore!==configForm.algorithmDefaults.prediction.templateRuntimePath) clearPrediction()
    await runAutomaticPrediction()
  } catch(e) { if(id===fieldId.value) error.value=e.message||'实时预测刷新失败' }
  finally { following=false }
}
async function showHistory(row) {
  const stored=decode(row.result_json)
  if(!stored.result?.predicted_pose&&!stored.result?.predicted_stations) { ElMessage.info(stored.result?.message||'该记录没有预测轨迹'); return }
  autoFollow.value=false; versionId.value=row.survey_version_id
  await changeVersion()
  task.value={...stored,task_id:row.task_id}; activeView.value='3d'
}
async function exportResults(){
  if (!task.value?.task_id) return
  try {
    const blob = await exportPredictionTrajectory(task.value.task_id)
    if (blob.type.includes('json')) throw new Error('导出失败，请刷新任务后重试')
    downloadBlob(blob,'井眼轨迹预测.csv')
  } catch (e) { ElMessage.error(e.message || '导出预测轨迹失败') }
}
watch(fieldId,()=>{clearPrediction();if(mounted){wellId.value=null;refresh()}})
onMounted(async()=>{try{if(!fieldId.value||!wells.value.length)await loadFields();form.step=Number(configForm.algorithmDefaults.prediction.predictionStep||9);form.device=configForm.algorithmDefaults.prediction.device||'auto';mounted=true;await refresh();timer=window.setInterval(follow,15000)}catch(e){error.value=e.message||'工作区初始化失败'}})
onBeforeUnmount(()=>{generation++;refreshGeneration++;window.clearInterval(timer)})
</script>
