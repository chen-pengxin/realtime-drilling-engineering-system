<template>
  <ResizableDialog v-model="open" title="轨迹可视化" width="min(1320px,calc(100vw - 32px))" append-to-body destroy-on-close>
    <div class="visual-toolbar"><span>{{ engineeringPath }}</span><el-button icon="Setting" @click="openSettings">可视化设置</el-button></div>
    <el-tabs v-model="visualView" @tab-change="resize">
      <el-tab-pane label="3D" name="3d"><div class="three-canvas" v-loading="loading"><TrajectoryPlot ref="threePlot" :trajectories="plotTrajectories" view="3d" :grid="grid" /></div></el-tab-pane>
      <el-tab-pane label="平面" name="plane"><div class="plane-canvases" v-loading="loading"><section><h4>垂直剖面</h4><TrajectoryPlot ref="verticalPlot" :trajectories="plotTrajectories" view="vertical" :azimuth="azimuth" :grid="true" /></section><section><h4>水平投影</h4><TrajectoryPlot ref="horizontalPlot" :trajectories="plotTrajectories" view="horizontal" :grid="true" /></section></div></el-tab-pane>
    </el-tabs>
    <template #footer><el-button @click="open=false">关闭</el-button></template>
  </ResizableDialog>
  <el-drawer v-model="settingsOpen" title="可视化设置" size="410px" append-to-body>
    <el-form label-position="top"><el-form-item label="显示轨迹"><el-tree-select v-model="selectedVersionIds" :data="selectionTree" node-key="value" show-checkbox multiple collapse-tags collapse-tags-tooltip :render-after-expand="false" filterable placeholder="区块 → 井场 → 井 → 井眼 → 轨迹类别 → 版本" style="width:100%" @change="loadSelectedTrajectories" /></el-form-item><el-form-item label="三维网格"><el-switch v-model="grid"/></el-form-item><el-form-item label="垂直剖面方位（°）"><el-input-number v-model="azimuth" :min="0" :max="360" :controls="false"/></el-form-item></el-form>
    <TrajectoryStyleSettings :trajectories="plotTrajectories"/>
  </el-drawer>
  <SurveyImportDialog ref="importDialog" :field-id="contextFieldId" :bore="bore" :kind="kind" :table-role="tableRole" :versions="sameKind" :default-reference="{}" @saved="saved" />
</template>
<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import {computed,nextTick,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {trajectoryVersion,boreNeighbors,boreVersions} from '@/api/drillingRegistry'
import {trajectoryIdentity,wellboreTree,wellboreName} from '@/utils/wellboreHierarchy'
import TrajectoryPlot from '@/components/TrajectoryPlot'
import TrajectoryStyleSettings from '../../layout/TrajectoryStyleSettings.vue'
import SurveyImportDialog from './SurveyImportDialog.vue'
const props=defineProps({fieldId:Number,node:Object,tableRole:String,engineeringPath:String,wellbores:{type:Array,default:()=>[]},versions:{type:Array,default:()=>[]},writableWellboreIds:{type:Array,default:()=>[]},canReadFiles:Boolean})
const emit=defineEmits(['saved'])
const open=ref(false),settingsOpen=ref(false),loading=ref(false),visualView=ref('3d'),grid=ref(true),azimuth=ref(0),kind=ref('ACTUAL'),contextFieldId=ref(null),contextNode=ref(null),contextVersions=ref([]),primary=ref(null),detail=ref(null),importDialog=ref(),threePlot=ref(),verticalPlot=ref(),horizontalPlot=ref(),available=ref([]),selectedVersionIds=ref([]),plotTrajectories=ref([])
const sameKind=computed(()=>contextVersions.value.filter(v=>v.trajectory_kind===kind.value))
const bore=computed(()=>({...contextNode.value,...primary.value,wellbore_id:primary.value?.wellbore_id||contextNode.value?.wellbore_id,wellbore_name:wellboreName({...contextNode.value,...primary.value,wellbore_name:primary.value?.wellbore_name||contextNode.value?.wellbore_name||contextNode.value?.node_name,wellbore_code:primary.value?.wellbore_code||contextNode.value?.wellbore_code||contextNode.value?.node_code}),engineering_path:props.engineeringPath}))
const selectionTree=computed(()=>{
 const tree=wellboreTree(available.value)
 function children(nodes){for(const n of nodes){if(n.row){const boreId=Number(n.value);const entries=available.value.filter(v=>Number(v.wellbore_id)===boreId);n.value=`bore:${boreId}`;n.children=['ACTUAL','DESIGN'].map(k=>({value:`kind:${n.value}:${k}`,label:k==='ACTUAL'?'实测测斜':'设计轨道',children:entries.filter(v=>v.trajectory_kind===k).map(v=>({value:String(v.survey_version_id),label:`v${v.version_no}${Number(v.is_current??v.is_active)?'（当前）':''}`}))})).filter(k=>k.children.length);delete n.row}else if(n.children)children(n.children)}}children(tree);return tree
})
let ticket=0,availableLoaded=false
const cache=new Map()
function capture(){contextFieldId.value=props.fieldId;contextNode.value={...props.node};contextVersions.value=props.versions.map(v=>({...v}));}
function identity(v){return {...props.wellbores.find(b=>Number(b.wellbore_id)===Number(v.wellbore_id)),...v}}
async function show(row){capture();ticket++;availableLoaded=false;cache.clear();primary.value={...row};kind.value=row.trajectory_kind;selectedVersionIds.value=[String(row.survey_version_id)];available.value=contextVersions.value.map(identity);plotTrajectories.value=[];settingsOpen.value=false;visualView.value='3d';open.value=true;await loadSelectedTrajectories();azimuth.value=Number(detail.value?.reference?.vertical_section_azimuth||0)}
async function openSettings(){settingsOpen.value=true;if(availableLoaded)return;loading.value=true;const current=ticket;try{
 const neighbors=await boreNeighbors(contextFieldId.value,bore.value.wellbore_id)
 const groups=await Promise.all((neighbors||[]).filter(n=>n.included!==false&&Number(n.included??1)!==0&&n.usable!==false).map(async n=>{
  const id=Number(n.wellbore_id||n.neighbor_wellbore_id),base=props.wellbores.find(b=>Number(b.wellbore_id)===id)||n
  const [actual,design]=await Promise.all(['ACTUAL','DESIGN'].map(k=>boreVersions(contextFieldId.value,id,k)))
  return [...(actual||[]),...(design||[])].map(v=>({...base,...v,wellbore_id:id,field_id:base.field_id||contextFieldId.value,trajectory_kind:v.trajectory_kind||((actual||[]).includes(v)?'ACTUAL':'DESIGN')}))
 }))
 if(current===ticket){available.value=[...new Map([...available.value,...groups.flat()].map(v=>[String(v.survey_version_id),v])).values()];availableLoaded=true}
 }catch(e){ElMessage.error(e.message||'读取邻井版本失败')}finally{if(current===ticket)loading.value=false}}
async function loadSelectedTrajectories(){const current=++ticket;loading.value=true;try{
 const rows=await Promise.all(selectedVersionIds.value.map(async id=>{
  const v=available.value.find(x=>String(x.survey_version_id)===String(id));if(!v)return null
  const key=`${primary.value.survey_version_id}:${id}`;let d=cache.get(key)
  if(!d){d=await trajectoryVersion(contextFieldId.value,Number(id),Number(primary.value.survey_version_id));cache.set(key,d)}
  if(Number(id)===Number(primary.value.survey_version_id))detail.value=d
  const neighbor=Number(v.wellbore_id)!==Number(primary.value.wellbore_id),type=v.trajectory_kind
  return {...trajectoryIdentity(v),id:`${neighbor?'neighbor':'reference'}-${id}`,kind:neighbor?'NEIGHBOR':type,trajectory_kind:type,label:`${v.well_name||v.well_code||''} / ${wellboreName(v)} · ${type==='DESIGN'?'设计':'实钻'} v${v.version_no}`,points:d.plot_stations||d.stations||[],key_mds:d.reference?.design_nodes?.map(p=>p.md)||[]}
 }))
 if(current===ticket)plotTrajectories.value=rows.filter(Boolean)
 }catch(e){ElMessage.error(e.message||'轨迹加载失败')}finally{if(current===ticket)loading.value=false}}
async function resize(){await nextTick();threePlot.value?.resize();verticalPlot.value?.resize();horizontalPlot.value?.resize()}
async function startImport(value,mode='AUTO',row=null){capture();primary.value=row||contextVersions.value.filter(v=>v.trajectory_kind===value).sort((a,b)=>Number(b.is_current)-Number(a.is_current)||Number(b.version_no)-Number(a.version_no))[0]||null;kind.value=value;await nextTick();if(!bore.value.wellbore_id)return ElMessage.warning('请选择井眼');importDialog.value.show(mode,row)}
function saved(v){emit('saved',v)}
defineExpose({show,startImport})
</script>
<style scoped>
.visual-toolbar{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:12px;font-size:13px}.three-canvas{height:min(64vh,650px);min-height:380px}.plane-canvases{display:grid;grid-template-columns:1fr 1fr;gap:16px;height:min(64vh,650px);min-height:380px}.plane-canvases section{min-width:0;height:100%}.plane-canvases h4{margin:0;height:26px;text-align:center}.plane-canvases .trajectory-plot{height:calc(100% - 26px)}@media(max-width:800px){.plane-canvases{grid-template-columns:1fr;height:720px}.plane-canvases section{height:350px}}
</style>
