<template>
  <DrillingPageFrame title="实时大屏">
    <el-tabs v-model="managementTab"><el-tab-pane v-for="tab in managementTabs" :key="tab.name" :name="tab.name" :label="tab.label" /></el-tabs>
    <EmbeddedDrilling v-if="managementTab==='alerts'"><AlertRecords /></EmbeddedDrilling>
    <section v-else ref="screenRef" class="live-screen" :class="{ 'is-fullscreen': fullscreen }">
      <header class="live-toolbar">
        <div><strong>实时测斜数据展示</strong><span>{{ selectedWellName }} · 数据更新时间 {{ refreshedAt }}</span></div>
        <div class="toolbar-actions"><el-tag :type="connectionType">{{ connectionLabel }}</el-tag><el-tag v-if="futureRisk" type="danger" effect="dark">预测防碰 {{ futureRisk.level }}</el-tag><el-button :type="alarmSoundEnabled ? 'success' : 'warning'" @click="enableAlarmSound">{{ alarmSoundEnabled ? '声音报警已启用' : '启用声音报警' }}</el-button><el-button :loading="loading" @click="refreshScreen()">刷新</el-button><el-button icon="Setting" @click="settingsOpen=!settingsOpen">视图设置</el-button><el-button icon="FullScreen" @click="toggleFullscreen">{{ fullscreen?'退出全屏':'全屏展示' }}</el-button></div>
      </header>

      <el-alert v-if="liveError" :title="liveError" type="warning" :closable="false" />
      <div class="live-grid" :class="{'with-settings':settingsOpen}">
        <aside class="telemetry-panel">
          <div class="panel-heading"><h3>实时数据</h3><span>最新测斜点</span></div>
          <WellboreSelect :model-value="boreId" :rows="bores" style="margin-bottom:12px" @change="live.selectBore" />
          <div class="telemetry-list">
            <div v-for="item in telemetry" :key="item.label" class="telemetry-item">
              <span>{{ item.label }}</span><b>{{ item.value }}<em v-if="item.unit"> {{ item.unit }}</em></b>
            </div>
          </div>
          <div class="telemetry-footer"><span>测斜版本</span><b>{{ surveyVersionLabel }}</b></div>
          <div class="telemetry-footer"><span>预测方式</span><b>{{ predictionSourceLabel }}</b></div>
        </aside>

        <main class="scene-panel">
          <div class="panel-heading"><h3>三维井眼轨迹</h3><span>{{ riskLabel }}</span></div>
          <div class="scene-wrap">
            <TrajectoryPlot v-if="sceneTrajectories.length" :trajectories="sceneTrajectories" :hidden="hidden" view="3d" :grid="grid" :nearest-pair="nearestPair" :risk-segments="riskSegments" />
            <div v-else class="panel-empty">暂无实时轨迹数据</div>
          </div>
          <div class="scene-footer"><span>参与邻井 {{ network?.neighbor_count ?? 0 }} 口</span><span v-if="neighborNotice">{{neighborNotice}}</span><span>最近中心距 {{ nearestDistance }}</span><span>全井网风险 <b :class="`risk-text-${riskLevel.toLowerCase()}`">{{ riskLevelText }}</b></span></div>
        </main>

        <aside v-if="settingsOpen" class="live-settings"><div class="panel-heading"><h3>可视化设置</h3><el-button text icon="Close" aria-label="关闭可视化设置" @click="settingsOpen=false" /></div><el-collapse :model-value="['styles','display']"><el-collapse-item title="轨迹显示" name="tracks"><TrajectoryVisibility v-model="hidden" :trajectories="sceneTrajectories" /></el-collapse-item><el-collapse-item title="轨迹样式" name="styles"><TrajectoryStyleSettings :trajectories="sceneTrajectories" /></el-collapse-item><el-collapse-item title="显示设置" name="display"><el-checkbox v-model="grid">坐标网格</el-checkbox></el-collapse-item></el-collapse></aside>
        <aside class="projection-panel">
          <section class="projection"><div class="panel-heading"><h3>垂直剖面</h3><el-input-number v-model="profileAzimuth" :min="0" :max="360" :precision="1" controls-position="right" aria-label="剖面方位角" style="width:110px"/></div><TrajectoryPlot :trajectories="sceneTrajectories" :hidden="hidden" view="vertical" :azimuth="profileAzimuth" :grid="grid" :risk-segments="riskSegments" /></section>
          <section class="projection"><div class="panel-heading"><h3>水平投影</h3><span>E–N</span></div><TrajectoryPlot :trajectories="sceneTrajectories" :hidden="hidden" view="horizontal" :grid="grid" :risk-segments="riskSegments" /></section>
        </aside>
      </div>
    </section>
  </DrillingPageFrame>
</template>

<script setup>
import TrajectoryVisibility from '../layout/TrajectoryVisibility.vue'
import TrajectoryStyleSettings from '@/views/drilling/layout/TrajectoryStyleSettings.vue'
import WellboreSelect from '@/views/drilling/layout/WellboreSelect.vue'
import {wellborePath} from '@/utils/wellboreHierarchy'
import {defineAsyncComponent} from 'vue'
import EmbeddedDrilling from '../layout/EmbeddedDrilling.vue'
import {useManagementTabs} from '../composables/useManagementTabs'
const AlertRecords=defineAsyncComponent(()=>import('../centers/alerts/index.vue'))
const {tabs:managementTabs,active:managementTab}=useManagementTabs([{name:'realtime',label:'实时展示'},{name:'alerts',label:'告警记录'}])

import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import DrillingPageFrame from '../layout/DrillingPageFrame.vue'
import TrajectoryPlot from '@/components/TrajectoryPlot'
import { useLiveDrilling } from '../composables/useLiveDrilling'
import { enableCollisionAlarmSound, playCollisionAlarm } from '@/utils/realtimeCollision'
const live=useLiveDrilling(),{bores,boreId,bore,detail:surveyVersion,latest:latestReading,network,prediction,futureRisk,trajectories:sceneTrajectories,loading,error:liveError,neighborNotice}=live
const settingsOpen=ref(false),hidden=ref([]),grid=ref(true)
const screenRef=ref(null),fullscreen=ref(false),alarmSoundEnabled=ref(false),profileAzimuth=ref(0)
const boreLabel=wellborePath
const selectedWellName=computed(()=>bore.value?boreLabel(bore.value):'暂无正钻井眼')
const refreshedAt=computed(()=>live.refreshedAt.value?new Intl.DateTimeFormat('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit'}).format(live.refreshedAt.value):'—')
const riskLevel=computed(()=>futureRisk.value?.level||network.value?.operational_risk_level||'未评价')
const riskLevelText=computed(()=>({RED:'危险',ORANGE:'警告',YELLOW:'关注',BLUE:'安全',UNKNOWN:'未完成评价'}[riskLevel.value]||riskLevel.value))
const riskLabel=computed(()=>network.value?`全井网风险 · ${riskLevelText.value}`:'未评价')
const connectionLabel=computed(()=>liveError.value?'待处理':network.value?'数据已同步':latestReading.value?'已接收测斜':'等待数据')
const connectionType=computed(()=>liveError.value?'warning':network.value?'success':'info')
const surveyVersionLabel=computed(()=>surveyVersion.value?`v${surveyVersion.value.version_no} · MD ${formatValue(surveyVersion.value.max_md)} m`:'—')
const predictionSourceLabel=computed(()=>prediction.value?.source==='TRAINED_MODEL'?'训练模型':prediction.value?.source==='MODEL_ERROR'?'模型执行失败':'未生成模型预测')
const nearestDistance=computed(()=>network.value?.nearest_pair?.min_distance!=null?`${formatValue(network.value.nearest_pair.min_distance)} m`:'—')
const nearestPair=computed(()=>network.value?.nearest_pair?{reference:network.value.nearest_pair.reference_point,neighbor:network.value.nearest_pair.neighbor_point}:null)
const riskSegments=computed(()=>network.value?.risk_segments||[])
const telemetry=computed(()=>{const row=latestReading.value||{},origin=surveyVersion.value?.stations?.[0];const displacement=origin&&row.north!=null&&row.east!=null?Math.hypot(Number(row.north)-Number(origin.north),Number(row.east)-Number(origin.east)):null;return [
{label:'测深 MD',value:formatValue(row.md),unit:'m'}, {label:'井斜角',value:formatValue(row.inclination),unit:'°'}, {label:'网格方位角',value:formatValue(row.azimuth),unit:'°'}, {label:'垂深 TVD',value:formatValue(row.tvd),unit:'m'}, {label:'水平位移',value:formatValue(displacement),unit:'m'}, {label:'北坐标',value:formatValue(row.north),unit:'m'}, {label:'东坐标',value:formatValue(row.east),unit:'m'}, {label:'模型预测 MD',value:prediction.value?.source==='TRAINED_MODEL'?formatValue(prediction.value?.point?.md):'—',unit:'m'}]})
function formatValue(v){return v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v))?Number(v).toFixed(2):'—'}
function refreshScreen(){return live.refresh({force:true})}
async function enableAlarmSound(){try{alarmSoundEnabled.value=await enableCollisionAlarmSound();if(alarmSoundEnabled.value)playCollisionAlarm();else ElMessage.warning('当前浏览器不支持声音报警')}catch{ElMessage.warning('请检查浏览器音频权限')}}
async function toggleFullscreen(){try{if(document.fullscreenElement)await document.exitFullscreen();else if(screenRef.value?.requestFullscreen)await screenRef.value.requestFullscreen();else throw Error('当前浏览器不支持屏幕全屏')}catch(e){ElMessage.warning(e.message)}}
function onFullscreenChange(){fullscreen.value=document.fullscreenElement===screenRef.value}
onMounted(()=>{document.addEventListener('fullscreenchange',onFullscreenChange);live.refresh()})
onBeforeUnmount(()=>document.removeEventListener('fullscreenchange',onFullscreenChange))
</script>

<style scoped>
.live-screen { display: grid; gap: 14px; min-height: 690px; }
.live-screen.is-fullscreen { width:100vw; height:100vh; min-height:100vh; padding:16px; overflow:auto; background:var(--el-bg-color); }
.live-toolbar, .telemetry-panel, .scene-panel, .projection, .screen-panel { border: 1px solid var(--el-border-color-light); background: var(--el-bg-color); }
.live-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 18px; }.live-toolbar strong { display: block; color: var(--el-text-color-primary); font-size: 14px; }.live-toolbar span { display: block; color: var(--el-text-color-regular); font-size: 12px; margin-top: 5px; }.toolbar-actions { display: flex; align-items: center; gap: 10px; flex-wrap:wrap; }
.live-grid { display: grid; grid-template-columns: 250px minmax(0, 1fr) 370px; gap: 14px; min-height: 640px; }
.telemetry-panel, .scene-panel, .projection { padding: 15px; min-width: 0; }.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 28px; margin-bottom: 12px; }.panel-heading h3 { margin: 0; color: var(--el-text-color-primary); font-size: 15px; }.panel-heading span { color: var(--el-text-color-regular); font-size: 12px; }
.telemetry-list { background: var(--el-fill-color-light); }.telemetry-item { padding: 10px 12px; border-bottom: 1px solid var(--el-border-color-light); text-align: right; }.telemetry-item span { display: block; color: var(--el-text-color-regular); font-size: 13px; text-align: left; }.telemetry-item b { display: block; margin-top: 4px; color: var(--el-color-primary); font-size: 22px;  }.telemetry-item em { color: var(--el-color-primary); font-size: 14px; font-style: normal; }.telemetry-footer { display: flex; justify-content: space-between; gap: 8px; padding: 12px 0 0; color: var(--el-text-color-regular); font-size: 12px; }.telemetry-footer b { color: var(--el-text-color-primary); text-align: right; }
.scene-panel { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; }.scene-wrap { min-height: 560px; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-light); }.scene-wrap :deep(.scene) { height: 100%; min-height: 560px; }.scene-footer { display: flex; flex-wrap: wrap; gap: 16px; padding-top: 12px; color: var(--el-text-color-regular); font-size: 12px; }.scene-footer b { color: var(--el-text-color-primary); }.risk-text-red { color: #dc2626 !important; }.risk-text-orange { color: #ea580c !important; }.risk-text-yellow { color: #ca8a04 !important; }.risk-text-blue { color: #2563eb !important; }
.projection-panel { display: grid; grid-template-rows: 1fr 1fr; gap: 14px; min-width: 0; }.projection { display: grid; grid-template-rows: auto minmax(0,1fr); min-height: 300px; }.projection-chart { min-height: 250px; }.panel-empty { display: flex; align-items: center; justify-content: center; height: 100%; color: var(--el-text-color-primary); }
@media (max-width: 1100px) { .live-grid { grid-template-columns: 220px minmax(0,1fr); }.projection-panel { grid-column: 1 / -1; grid-template-columns: 1fr 1fr; grid-template-rows: none; }.projection { min-height: 280px; } }
@media (max-width: 760px) { .live-toolbar { align-items: flex-start; flex-direction: column; }.live-grid { display: block; }.telemetry-panel, .scene-panel, .projection-panel { margin-bottom: 14px; }.projection-panel { display: block; }.projection { margin-bottom: 14px; }.scene-wrap, .scene-wrap :deep(.scene) { min-height: 400px; height: 400px; } }
</style>

<style scoped>.live-grid.with-settings{grid-template-columns:250px minmax(250px,1fr) 250px 320px}.live-settings{border:1px solid var(--el-border-color-light);padding:12px;min-width:0}@media(max-width:1350px){.live-grid.with-settings{grid-template-columns:220px minmax(240px,1fr) 220px}.live-grid.with-settings .projection-panel{grid-column:1/-1;grid-template-columns:1fr 1fr}}@media(max-width:760px){.live-grid.with-settings{grid-template-columns:1fr}.live-grid.with-settings .projection-panel{grid-template-columns:1fr}}</style>
