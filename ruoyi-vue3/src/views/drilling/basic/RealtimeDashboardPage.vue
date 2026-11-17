<template>
  <DrillingPageFrame title="实时数据接入">
    <el-tabs v-model="managementTab"><el-tab-pane v-for="tab in managementTabs" :key="tab.name" :name="tab.name" :label="tab.label" /></el-tabs>
    <EmbeddedDrilling v-if="managementTab==='connectors'"><ConnectorManager /></EmbeddedDrilling>
    <section v-else class="dashboard-grid">
      <div class="panel connection-panel">
        <div class="panel-title"><span>连接状态</span><div class="status-tags"><el-tag :type="result ? 'success' : 'info'">{{ result ? '已接收批次' : '等待数据' }}</el-tag><el-tag v-if="futureRisk" type="danger" effect="dark">预测风险 {{ futureRisk.level }}</el-tag></div></div>
        <el-button :type="alarmSoundEnabled ? 'success' : 'warning'" class="sound-toggle" @click="enableAlarmSound">{{ alarmSoundEnabled ? '声音报警已启用' : '启用声音报警' }}</el-button>
        <el-form :model="form" label-position="top">
          <el-form-item label="数据源"><el-input v-model="form.source" placeholder="WITS-TCP / WITSML-2.0 / SERIAL" /></el-form-item>
          <el-form-item label="接入井眼"><WellboreSelect v-model="form.wellbore_id" :rows="bores" /></el-form-item>
          <el-form-item label="乱序窗口（m）"><el-input-number v-model="form.reorder_window" :min="0" :max="1000" style="width:100%" /></el-form-item>
          <el-form-item><el-checkbox v-model="form.flush">提交后立即刷新测斜版本</el-checkbox></el-form-item>
          <el-button type="primary" :loading="loading" @click="ingest">提交实时批次</el-button>
        </el-form>
      </div>
      <div class="panel event-panel">
        <div class="panel-title"><span>事件批次</span><span class="muted">重复、乱序、非法事件会隔离</span></div>
        <el-input v-model="form.eventsText" type="textarea" :rows="13" />
        <el-alert v-if="error" :title="error" type="error" :closable="false" class="alert" />
        <el-alert v-if="result" :title="`接收 ${result.accepted?.length || 0} 条 · 重复 ${result.duplicate_events ?? result.duplicates ?? 0} 条 · 乱序 ${result.out_of_order_events?.length || 0} 条`" type="success" :closable="false" class="alert" />
        <el-alert v-if="collisionError" :title="collisionError" type="warning" :closable="false" class="alert" />
      </div>
      <div class="panel status-panel">
        <div class="panel-title">实时处理摘要</div>
        <el-descriptions v-if="result" :column="1" border><el-descriptions-item label="持久化">{{ result.persistence_status || '—' }}</el-descriptions-item><el-descriptions-item label="测斜版本">{{ result.persisted_survey_version_id || '—' }}</el-descriptions-item><el-descriptions-item label="缓冲">{{ result.buffered?.length || 0 }}</el-descriptions-item><el-descriptions-item label="无效">{{ result.invalid?.length || 0 }}</el-descriptions-item></el-descriptions>
        <template v-if="collisionRefresh"><el-divider/><div class="panel-title">实时防碰刷新</div><el-tag :type="riskType(collisionRefresh.operational_risk_level)">{{ collisionRefresh.operational_risk_level }}</el-tag><el-tag v-if="futureRisk" type="danger" class="future-risk-tag">预测钻头位置 {{ futureRisk.level }}</el-tag><p class="refresh-summary">最近中心距 {{ Number(collisionRefresh.min_distance??collisionRefresh.minimum_distance).toFixed(2) }} m · {{ collisionRefresh.candidate_count }} 口候选邻井</p><p class="refresh-summary">依赖核对后复用 {{ collisionRefresh.incremental?.reused_blocks??0 }} 块，重算 {{ collisionRefresh.incremental?.recomputed_blocks??0 }} 块</p><div class="refresh-grid"><div class="risk-band"><b>风险井对</b><span v-for="item in riskBand" :key="item.level" :class="item.level.toLowerCase()">{{ item.level }} {{ item.count }}</span></div><div class="refresh-charts"><TrajectoryPlot :trajectories="refreshTrajectories" view="vertical" :risk-segments="refreshRiskSegments"/><TrajectoryPlot :trajectories="refreshTrajectories" view="horizontal" :risk-segments="refreshRiskSegments"/></div><TrajectoryPlot :trajectories="refreshTrajectories" view="3d" :nearest-pair="refreshNearestPair" :risk-segments="refreshRiskSegments" /></div></template>
        <div v-else class="empty-result">尚未提交实时事件</div>
      </div>
    </section>
    <el-collapse v-if="result && managementTab==='realtime-data'" class="raw-panel"><el-collapse-item title="数据明细" name="details"><pre>{{ JSON.stringify(result, null, 2) }}</pre></el-collapse-item></el-collapse>
  </DrillingPageFrame>
</template>

<script setup>
import WellboreSelect from '@/views/drilling/layout/WellboreSelect.vue'
import {defineAsyncComponent} from 'vue'
import EmbeddedDrilling from '../layout/EmbeddedDrilling.vue'
import {useManagementTabs} from '../composables/useManagementTabs'
const ConnectorManager=defineAsyncComponent(()=>import('../centers/connectors/index.vue'))
const {tabs:managementTabs,active:managementTab}=useManagementTabs([{name:'realtime-data',label:'实时数据批次'},{name:'connectors',label:'连接器配置'}])

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ingestRealtime } from '@/api/drilling'
import { listWellbores } from '@/api/drillingRegistry'
import TrajectoryPlot from '@/components/TrajectoryPlot'
import DrillingPageFrame from '../layout/DrillingPageFrame.vue'
import { useDrillingContext } from '../composables/useDrillingContext'
import { useLiveDrilling } from '../composables/useLiveDrilling'
import { enableCollisionAlarmSound,playCollisionAlarm } from '@/utils/realtimeCollision'
const {fieldId}=useDrillingContext(),live=useLiveDrilling(),bores=ref([])
const loading=ref(false),error=ref(''),result=ref(null),collisionError=ref(''),alarmSoundEnabled=ref(false)
const collisionRefresh=live.network,futureRisk=live.futureRisk,refreshTrajectories=live.trajectories
const form=reactive({source:'WITS-TCP',wellbore_id:null,reorder_window:3,flush:true,eventsText:'[\n  { "md": 1200, "inclination": 35.2, "azimuth": 128.4 }\n]'})
const boreLabel=b=>[b?.block_name,b?.field_name,b?.well_name||b?.well_code,b?.wellbore_name||b?.wellbore_code].filter(Boolean).join(' / ')
let requestSerial=0
async function loadBores(){const p=fieldId.value,n=++requestSerial;bores.value=[];form.wellbore_id=null;result.value=null;if(!p)return;try{const rows=await listWellbores(p);if(n!==requestSerial)return;bores.value=rows.filter(r=>r.actual_version_id);form.wellbore_id=bores.value.find(r=>r.engineering_status==='DRILLING')?.wellbore_id||bores.value[0]?.wellbore_id}catch(e){error.value=e.message}}
async function ingest(){
 const selected=bores.value.find(b=>b.wellbore_id===form.wellbore_id)
 if(!fieldId.value||!selected){ElMessage.warning('请选择已有实钻基线的井眼');return}
 let events;try{events=JSON.parse(form.eventsText||'[]');if(!Array.isArray(events))throw Error('事件必须是数组')}catch(e){error.value=e.message;return}
 loading.value=true;error.value='';collisionError.value=''
 try{
  alarmSoundEnabled.value=await enableCollisionAlarmSound().catch(()=>false)
  result.value=await ingestRealtime({field_id:fieldId.value,well_id:selected.well_id,wellbore_id:selected.wellbore_id,source:form.source,reorder_window:form.reorder_window,flush:form.flush,events})
  ElMessage.success('实时批次已处理')
  if(result.value.persisted_survey_version_id){
   await live.selectBore(selected.wellbore_id)
   const rows=[...(result.value.accepted||[]),...(result.value.flushed||[])].map(r=>Number(r.md)).filter(Number.isFinite)
   await live.refresh({force:true,wellboreId:selected.wellbore_id,incrementalFrom:rows.length?Math.min(...rows):undefined,incrementalTo:rows.length?Math.max(...rows):undefined})
   collisionError.value=live.error.value
   window.dispatchEvent(new CustomEvent('drilling-survey-updated'))
  }
 }catch(e){error.value=e.message||'实时批次处理失败'}finally{loading.value=false}
}
async function enableAlarmSound(){try{alarmSoundEnabled.value=await enableCollisionAlarmSound();if(alarmSoundEnabled.value)playCollisionAlarm();else ElMessage.warning('当前浏览器不支持声音报警')}catch{ElMessage.warning('请检查浏览器音频权限')}}
function riskType(level){return ({RED:'danger',ORANGE:'warning',YELLOW:'warning',BLUE:'success'}[level]||'info')}
const refreshNearestPair=computed(()=>collisionRefresh.value?.nearest_pair?{reference:collisionRefresh.value.nearest_pair.reference_point,neighbor:collisionRefresh.value.nearest_pair.neighbor_point}:null)
const refreshRiskSegments=computed(()=>collisionRefresh.value?.risk_segments||[])
const riskBand=computed(()=>['RED','ORANGE','YELLOW','BLUE'].map(level=>({level,count:collisionRefresh.value?.results?.filter(r=>r.operating_risk_level===level).length||0})))
watch(fieldId,loadBores,{immediate:true})
</script>

<style scoped>
.dashboard-grid{display:grid;grid-template-columns:280px minmax(0,1fr) 280px;gap:14px}.panel{padding:16px;border:1px solid var(--el-border-color-light);background:var(--el-bg-color);min-width:0}.panel-title{display:flex;justify-content:space-between;align-items:center;color:var(--el-text-color-primary);font-weight:600;margin-bottom:14px}.status-tags{display:flex;gap:6px;align-items:center}.sound-toggle{margin:-5px 0 14px}.muted{color:var(--el-text-color-regular);font-size:12px}.alert{margin-top:12px}.refresh-summary{margin:8px 0 0;color:var(--el-text-color-regular);font-size:12px;line-height:1.6}.future-risk-tag{margin-left:8px}.refresh-grid{margin-top:12px}.risk-band{display:flex;gap:6px;align-items:center;flex-wrap:wrap;color:var(--el-text-color-regular);font-size:12px}.risk-band b{width:100%;color:var(--el-text-color-regular)}.risk-band span{padding:3px 6px;color:#fff;font-weight:700}.risk-band .red{background:#dc2626}.risk-band .orange{background:#ea580c}.risk-band .yellow{background:#ca8a04}.risk-band .blue{background:#2563eb}.refresh-charts{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px}.refresh-charts>div{height:180px;min-width:0;border:1px solid var(--el-border-color-light)}.refresh-grid :deep(.scene){height:230px;margin-top:8px}.raw-panel{margin-top:14px}.raw-panel pre{max-height:300px;overflow:auto;color:var(--el-text-color-regular);background:var(--el-fill-color-light);padding:12px}@media(max-width:1050px){.dashboard-grid{grid-template-columns:1fr 1fr}.status-panel{grid-column:1/-1}}@media(max-width:680px){.dashboard-grid{display:block}.panel{margin-bottom:12px}.refresh-charts{grid-template-columns:1fr}.refresh-charts>div{height:210px}}
</style>
