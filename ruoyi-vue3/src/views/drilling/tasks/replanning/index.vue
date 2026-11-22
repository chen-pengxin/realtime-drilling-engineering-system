<template>
  <DrillingPageFrame title="轨迹再规划">
    <EngineeringWorkspace ref="workspace" task-type="replanning"
      :title="title"
      :well-label="boreLabel"
      :datum="datum"
      :has-result="!!result"
      :trajectories="planTrajectories"
      :original-trajectories="originalTrajectories"
      :show-original="true"
      :risk-segments="riskSegments"
      :risk-label="riskLabel"
      :minimum-sf="minimumSf"
      @save="saveDraft"
      @rename="renameDraft"
      @history="showHistory"
      @export="exportBundle($event?.format||'bundle', $event?.sections)"
      @refresh="task.loadBores"
    >
      <template #input>
        <el-alert v-if="realtimeAlert" title="已从实时防碰预警进入绕障再规划" :description="realtimeAlertDescription" type="warning" show-icon :closable="false" />
        <el-form label-position="top" class="task-form"><el-collapse v-model="inputSections"><el-collapse-item title="井眼与测斜版本" name="well">
          <el-form-item label="正钻井眼"><el-cascader v-model="task.borePath.value" :options="task.wellboreOptions.value" :props="{emitPath:true,checkStrictly:false}" :show-all-levels="true" filterable clearable placeholder="区块 / 井场 / 井 / 井眼" style="width:100%" /></el-form-item>
          <el-form-item label="当前实钻版本"><el-select v-model="versionId" filterable style="width:100%" @change="task.loadDetail"><el-option v-for="v in versions" :key="v.survey_version_id" :value="v.survey_version_id" :label="`v${v.version_no} · MD ${num(v.max_md)} m`" /></el-select></el-form-item>
          <div v-if="start" class="start-state"><b>井底 MD {{ num(start.md) }} m</b><span>INC {{ num(start.inclination) }}° · AZI {{ num(start.azimuth) }}°</span><small>N {{ num(start.north) }} · E {{ num(start.east) }} · TVD {{ num(start.tvd) }}</small></div>
          </el-collapse-item><el-collapse-item title="邻井与防碰规则" name="neighbors"><el-form-item label="分离系数阈值"><el-input-number v-model="form.minimumSf" :min="0.01" :step="0.1" /><div class="field-help">再规划只使用分离系数作为碰撞约束</div></el-form-item><el-form-item label="参与规划的邻井"><NeighborSelector :field-id="fieldId" v-model="neighbors" @change="task.loadNeighborDetails" @save="saveNeighbors" /></el-form-item>
          </el-collapse-item><el-collapse-item title="求解设置" name="solver"><el-form-item label="求解器"><el-select v-model="form.solver" style="width:100%"><el-option v-for="s in solverOptions" :key="s" :label="s" :value="s" /></el-select></el-form-item>
          <div class="two-fields"><el-form-item label="轨迹模型"><el-select v-model="nativeTrajectoryMode" style="width:100%"><el-option v-for="mode in trajectoryModes" :key="mode" :label="trajectoryModeLabel(mode)" :value="mode" /></el-select></el-form-item><el-form-item label="轨迹段数"><el-select v-model="nativeSegmentCount" style="width:100%"><el-option v-for="count in segmentCounts" :key="count" :label="`${count} 段（${segmentSequence(count)}）`" :value="count" /></el-select></el-form-item></div>
          <el-form-item label="最大迭代次数"><el-input-number v-model="form.maxIterations" :min="1" :max="5000" /></el-form-item></el-collapse-item><el-collapse-item title="目标点与终端姿态" name="target">
          <div class="three-fields"><el-form-item label="North（m）"><el-input-number v-model="form.north" :controls="false" /></el-form-item><el-form-item label="East（m）"><el-input-number v-model="form.east" :controls="false" /></el-form-item><el-form-item label="TVD（m）"><el-input-number v-model="form.tvd" :controls="false" /></el-form-item></div>
          <el-form-item label="目标容差（m）"><el-input-number v-model="form.targetTolerance" :min="0.01" :step="0.1" /></el-form-item>
          <el-checkbox v-model="form.constrainAttitude">约束终端井斜与方位</el-checkbox><div v-if="form.constrainAttitude" class="two-fields"><el-form-item label="目标井斜（°）"><el-input-number v-model="form.targetInclination" :min="0.001" :max="179.999" /></el-form-item><el-form-item label="目标方位（°）"><el-input-number v-model="form.targetAzimuth" :min="0" :max="359.999" /></el-form-item></div></el-collapse-item><el-collapse-item title="段长与曲率约束" name="bounds">
          <el-radio-group v-model="constraintMode" size="small"><el-radio-button value="GLOBAL">全局约束</el-radio-button><el-radio-button value="PER_SEGMENT">分段约束</el-radio-button></el-radio-group><div v-if="constraintMode==='GLOBAL'" class="two-fields"><el-form-item label="最小段长（m）"><el-input-number v-model="form.minSegmentLength" :min="0.1" /></el-form-item><el-form-item label="最大段长（m）"><el-input-number v-model="form.maxSegmentLength" :min="0.1" /></el-form-item><el-form-item label="最小狗腿度"><el-input-number v-model="form.minDogleg" :min="0" :step="0.1" /></el-form-item><el-form-item label="最大狗腿度（°/30m）"><el-input-number v-model="form.maxDogleg" :min="0.1" :step="0.1" /></el-form-item></div>
          <el-divider>逐段约束（按算法引擎配置的轨迹段数）</el-divider>
          <div v-if="constraintMode==='PER_SEGMENT'" v-for="(bound,index) in segmentConstraints" :key="index" class="segment-bound">
            <div class="drilling-toolbar"><b>第 {{bound.segment}} 段</b><el-button link type="danger" @click="segmentConstraints.splice(index,1)">移除</el-button></div>
            <el-form-item label="段号"><el-input-number v-model="bound.segment" :min="1" :max="engineSegmentCount" /></el-form-item>
            <div class="two-fields"><el-form-item label="段长下限（m）"><el-input-number v-model="bound.minLength" :min="0.1" /></el-form-item><el-form-item label="段长上限（m）"><el-input-number v-model="bound.maxLength" :min="0.1" /></el-form-item><el-form-item label="狗腿度下限"><el-input-number v-model="bound.minDls" :min="0" :step="0.1" /></el-form-item><el-form-item label="狗腿度上限"><el-input-number v-model="bound.maxDls" :min="0" :step="0.1" /></el-form-item></div>
          </div>
          <el-button v-if="constraintMode==='PER_SEGMENT'" plain icon="Plus" :disabled="segmentConstraints.length>=engineSegmentCount" @click="addConstraint">添加分段约束</el-button>
          </el-collapse-item>
        </el-collapse>
          <div v-if="pendingNeighborConfirmation" class="field-help">所选范围候选尚未保存确认，确认后才能用于规划计算。</div><el-button type="primary" :loading="busy" :disabled="pendingNeighborConfirmation||!detail||detail.quality_status!=='READY'" style="width:100%;margin-top:12px" v-hasPermi="['drilling:plan:run']" @click="solve">生成规划方案</el-button>
        <p v-if="busy && solveStarted" class="field-help" role="status">正在求解并复核，已用 {{ elapsedSeconds }} 秒；任务时限 {{ taskTimeout }} 秒。</p><el-alert v-if="solveError" :title="solveError" type="error" :closable="false" style="margin-top:10px" />
        </el-form>
      </template>
      <template #solutions>
        <el-descriptions :column="1" border class="solution-descriptions">
          <el-descriptions-item label="状态">{{ displayResult?.status || '未生成' }}</el-descriptions-item><el-descriptions-item label="求解器">{{ displayResult?.solver_metadata?.actual_solver || displayResult?.solver || form.solver }}</el-descriptions-item><el-descriptions-item label="轨迹几何">{{ displayResult?.solver_metadata?.configuration?.trajectory_mode || '由算法配置决定' }}</el-descriptions-item><el-descriptions-item label="轨迹段数">{{ displayResult?.segments?.length || '—' }}</el-descriptions-item><el-descriptions-item label="目标函数">轨迹总长度最小化</el-descriptions-item><el-descriptions-item label="轨迹总长">{{ num(displayResult?.total_length) }} m</el-descriptions-item><el-descriptions-item label="终端误差">{{ num(displayResult?.target_error) }} m</el-descriptions-item><el-descriptions-item label="最小 SF">{{ num(minimumSf) }}</el-descriptions-item><el-descriptions-item label="迭代次数">{{ displayResult?.iterations ?? '—' }}</el-descriptions-item><el-descriptions-item label="耗时">{{ displayResult?.elapsed_seconds == null ? '—' : `${num(displayResult.elapsed_seconds)} s` }}</el-descriptions-item><el-descriptions-item label="约束审计">{{ displayResult ? (displayResult.constraint_violations?.length ? displayResult.constraint_violations.join('；') : '通过') : '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-collapse class="segment-results"><el-collapse-item v-for="segment in displayResult?.segments||[]" :key="segment.segment" :title="`第 ${segment.segment} 段求解参数`"><el-descriptions :column="1" border size="small"><el-descriptions-item label="段型">{{segment.type||'—'}}</el-descriptions-item><el-descriptions-item label="段长">{{num(segment.length)}} m</el-descriptions-item><el-descriptions-item label="狗腿度">{{num(segment.dogleg)}} °/30m</el-descriptions-item><el-descriptions-item label="起始工具面">{{num(segment.start_toolface_deg??segment.toolface)}} °</el-descriptions-item><el-descriptions-item label="滑动 / 复合建议">{{segment.sliding_ratio==null?'—':num(segment.sliding_ratio*100)}}% / {{segment.composite_ratio==null?'—':num(segment.composite_ratio*100)}}%</el-descriptions-item></el-descriptions></el-collapse-item></el-collapse>
        <el-divider />
        <el-descriptions :column="1" border><el-descriptions-item label="版本输入">{{ inputVersionSummary }}</el-descriptions-item><el-descriptions-item label="数据来源">{{ currentVersion?.source_format || '数据库测斜版本' }}</el-descriptions-item></el-descriptions>
      </template>
      <template #collision><CollisionResults :network="displayResult?.collision_evaluation" :neighbors="neighbors" /><el-empty v-if="!displayResult?.collision_evaluation" :description="displayResult?'当前方案未选有效邻井，无防碰扫描结果':'生成方案后显示防碰扫描结果'" /></template>
      <template #table>
        <div class="drilling-toolbar"><el-button :disabled="!displayResult?.trajectory?.length" icon="Download" @click="exportBundle('csv')">导出 CSV</el-button><el-button :disabled="!displayResult?.trajectory?.length" icon="Download" @click="exportBundle('witsml')">导出 WITSML</el-button></div>
          <el-table :data="tableRows" height="100%" empty-text="生成方案后显示轨迹设计表"><el-table-column prop="sequence" label="序号" width="64"/><el-table-column prop="md" label="MD（m）" min-width="100"/><el-table-column prop="north" label="North（m）" min-width="110"/><el-table-column prop="east" label="East（m）" min-width="110"/><el-table-column prop="tvd" label="TVD（m）" min-width="100"/><el-table-column prop="inclination" label="井斜（°）" min-width="95"/><el-table-column prop="azimuth" label="方位（°）" min-width="95"/><el-table-column prop="dogleg" label="狗腿度（°/30m）" min-width="135"/><el-table-column prop="toolface" label="工具面（°）" min-width="105"/><el-table-column prop="slide" label="滑动比例" min-width="95"/><el-table-column prop="composite" label="复合比例" min-width="95"/><el-table-column prop="constraint" label="约束状态" min-width="90"/></el-table>
      </template>
      <template #context>
        <el-descriptions :column="1" border><el-descriptions-item label="工程路径">{{ boreLabel || '—' }}</el-descriptions-item><el-descriptions-item label="实钻版本">{{ currentVersionLabel }}</el-descriptions-item><el-descriptions-item label="最新 MD">{{ num(start?.md) }} m</el-descriptions-item><el-descriptions-item label="数据时间">{{ start?.recorded_at || start?.survey_time || '—' }}</el-descriptions-item></el-descriptions>
        <el-tag :type="start ? 'success' : 'info'" class="source-tag">{{ start ? '已加载实钻测斜版本' : '尚未加载测斜版本' }}</el-tag>
      </template>
      <template #bottom><span>规划方案与原始方案、邻井轨迹均来自已选版本。</span></template>
    </EngineeringWorkspace>
    <el-drawer v-model="historyOpen" title="再规划任务历史" size="520px"><el-table :data="history" empty-text="暂无历史任务" @row-dblclick="restoreHistory"><el-table-column prop="record_id" label="任务" width="90"/><el-table-column prop="status" label="状态"/><el-table-column prop="created_at" label="时间"/><el-table-column label="操作"><template #default="s"><el-button link @click="restoreHistory(s.row)">查看结果</el-button></template></el-table-column></el-table></el-drawer>
  </DrillingPageFrame>
</template>
<script setup>
import {trajectoryIdentity} from '@/utils/wellboreHierarchy'
import {computed,reactive,ref,watch,onBeforeUnmount} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage} from 'element-plus'
import {solveTrajectoryPlan,exportSurvey,exportDrillingReport} from '@/api/drilling'
import DrillingPageFrame from '../../layout/DrillingPageFrame.vue'
import EngineeringWorkspace from '../../layout/EngineeringWorkspace.vue'
import NeighborSelector from '../../layout/NeighborSelector.vue'
import CollisionResults from '../../layout/CollisionResults.vue'
import {useTrajectoryTask} from '../../composables/useTrajectoryTask'
import {engineCatalog} from '@/api/drillingRegistry'
import {downloadBlob} from '@/api/drillingRegistry'
import {trajectoryColors} from '@/utils/trajectoryColors'
const route=useRoute(),task=useTrajectoryTask('replanning'),{fieldId,currentFieldName,configForm,bores,boreId,bore,versions,versionId,detail,design,neighbors,selectedNeighbors,baseTrajectories,title,result,busy,save,rename,showHistory,historyOpen,history,label,saveNeighbors}=task
const workspace=ref(),inputSections=ref(['well','target','bounds']),constraintMode=ref('GLOBAL'),segmentConstraints=ref([]),catalog=ref({algorithms:['SLSQP'],trajectory_modes:['arc','arc-straight','straight-arc'],segment_counts:[1,2,3,4,5]}),nativeTrajectoryMode=ref('arc-straight'),nativeSegmentCount=ref(3),form=reactive({solver:'SLSQP',north:0,east:0,tvd:0,targetTolerance:.5,minSegmentLength:3,maxSegmentLength:30,minDogleg:0,maxDogleg:6,minimumSf:1.5,maxIterations:500,constrainAttitude:false,targetInclination:1,targetAzimuth:0})
// Geometry and segment count are native engine capabilities, not business
// planning scenarios.
const trajectoryModes=computed(()=>catalog.value.trajectory_modes||['arc','arc-straight','straight-arc']),segmentCounts=computed(()=>catalog.value.segment_counts||[1,2,3,4,5]),engineSegmentCount=computed(()=>Number(nativeSegmentCount.value||3)),solverOptions=computed(()=>catalog.value.algorithms?.filter(s=>['SLSQP','trust-constr','COBYLA','COBYQA'].includes(s))||['SLSQP'])
const trajectoryModeLabel=mode=>({arc:'圆弧','arc-straight':'圆弧—直线','straight-arc':'直线—圆弧'}[mode]||mode)
const segmentSequence=(count)=>{
  const n=Number(count||1)
  if(nativeTrajectoryMode.value==='arc') return Array.from({length:n},()=> '圆弧').join('—')
  const first=nativeTrajectoryMode.value==='straight-arc'?'直线':'圆弧'
  const other=first==='直线'?'圆弧':'直线'
  return Array.from({length:n},(_,index)=>index%2===0?first:other).join('—')
}
const realtimeAlert=computed(()=>String(route.query.alert||'')==='collision'),realtimeAlertDescription=computed(()=>`预测钻头 MD ${route.query.predictedMd||'—'} m，风险邻井 ${route.query.neighborId||'—'}。请确认终点和分段约束后生成方案。`),start=computed(()=>detail.value?.stations?.at(-1)),currentVersion=computed(()=>versions.value.find(v=>Number(v.survey_version_id)===Number(versionId.value))),boreLabel=computed(()=>bore.value?label(bore.value):''),datum=computed(()=>detail.value?.reference?.depth_datum||'—'),currentVersionLabel=computed(()=>currentVersion.value?`v${currentVersion.value.version_no} · MD ${num(currentVersion.value.max_md)} m`:'—'),inputVersionSummary=computed(()=>`${versionId.value||'—'} + ${selectedNeighbors.value.length} 个邻井版本`)
const pendingNeighborConfirmation=computed(()=>neighbors.value.some(n=>n.included&&n.usable&&!n.relationship_confirmed))
const displayResult=result
const riskSegments=computed(()=>displayResult.value?.collision_evaluation?.risk_segments||[]),riskLabel=computed(()=>displayResult.value?.collision_evaluation?.operational_risk_level||(displayResult.value?(displayResult.value.status==='SUCCESS'||displayResult.value.status==='feasible_design'?'BLUE':'YELLOW'):'未评价')),minimumSf=computed(()=>displayResult.value?.solver_metadata?.minimum_sf??displayResult.value?.minimum_sf??null)
const originalTrajectories=computed(()=>baseTrajectories.value.filter(t=>t.kind==='DESIGN'||t.kind==='ACTUAL'||t.id!=='planned'))
const planTrajectories=computed(()=>(result.value?.trajectory?.length?[...baseTrajectories.value,{...trajectoryIdentity(task.bore.value),id:'planned',label:'再规划方案',kind:'PLANNED',points:result.value.trajectory,key_mds:(result.value.segments||[]).flatMap(s=>[s.start_md,s.end_md]),color:trajectoryColors.PLANNED}]:baseTrajectories.value))
const tableRows=computed(()=>{const rows=displayResult.value?.trajectory||[],segments=displayResult.value?.segments||[];return rows.map((r,i)=>{const s=segments.find(x=>Number(x.segment)===Number(r.segment))||segments[i-1]||{};return {sequence:i+1,md:num(r.md),north:num(r.north),east:num(r.east),tvd:num(r.tvd),inclination:num(r.inclination),azimuth:num(r.azimuth),dogleg:num(r.dogleg_deg_per_30m??r.dogleg),toolface:num(r.toolface??s.start_toolface_deg??s.toolface),slide:s.sliding_ratio==null?'—':`${(Number(s.sliding_ratio)*100).toFixed(1)}%`,composite:s.composite_ratio==null?'—':`${(Number(s.composite_ratio)*100).toFixed(1)}%`,constraint:s.constraint_status||s.constraintStatus||'通过'}})})
function num(v){return v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v))?Number(v).toFixed(2):'—'}
function addConstraint(){const used=new Set(segmentConstraints.value.map(r=>r.segment));const segment=Array.from({length:engineSegmentCount.value},(_,i)=>i+1).find(i=>!used.has(i));if(segment)segmentConstraints.value.push({segment,minLength:form.minSegmentLength,maxLength:form.maxSegmentLength,minDls:form.minDogleg,maxDls:form.maxDogleg})}
function boundsPayload(){if(constraintMode.value==='GLOBAL')return [];const rows=Array.from({length:engineSegmentCount.value},()=>({})),seen=new Set();for(const r of segmentConstraints.value){if(!Number.isInteger(r.segment)||r.segment<1||r.segment>engineSegmentCount.value||seen.has(r.segment)||r.minLength>r.maxLength||r.minDls>r.maxDls)throw Error('逐段约束段号不得重复，且下限不能大于上限');seen.add(r.segment);rows[r.segment-1]={length_m:[r.minLength,r.maxLength],dls_deg_per_30m:[r.minDls,r.maxDls]}}if(seen.size!==engineSegmentCount.value)throw Error('分段约束模式必须为每个轨迹段提供约束');return rows}
async function restoreHistory(row){const stored=await task.restoreHistory(row);if(!stored)return;const p=stored.input;nativeTrajectoryMode.value=p.native_options?.trajectory_mode||nativeTrajectoryMode.value;nativeSegmentCount.value=Number(p.native_options?.n_segments||nativeSegmentCount.value);constraintMode.value=p.constraint_mode||((p.segment_bounds||[]).length?'PER_SEGMENT':'GLOBAL');Object.assign(form,{solver:p.solver||form.solver,north:p.target?.north??form.north,east:p.target?.east??form.east,tvd:p.target?.tvd??form.tvd,targetTolerance:p.target_tolerance_m??form.targetTolerance,minSegmentLength:p.min_segment_length??form.minSegmentLength,maxSegmentLength:p.max_segment_length??form.maxSegmentLength,minDogleg:p.min_dogleg_deg_per_30m??form.minDogleg,maxDogleg:p.max_dogleg_deg_per_30m??form.maxDogleg,minimumSf:p.minimum_sf??form.minimumSf,maxIterations:p.max_iterations??form.maxIterations,constrainAttitude:p.target_inclination!=null||p.target_azimuth!=null,targetInclination:p.target_inclination??form.targetInclination,targetAzimuth:p.target_azimuth??form.targetAzimuth});segmentConstraints.value=(p.segment_bounds||[]).slice(0,engineSegmentCount.value).flatMap((r,i)=>Object.keys(r).length?[{segment:i+1,minLength:r.length_m?.[0]??form.minSegmentLength,maxLength:r.length_m?.[1]??form.maxSegmentLength,minDls:r.dls_deg_per_30m?.[0]??form.minDogleg,maxDls:r.dls_deg_per_30m?.[1]??form.maxDogleg}]:[]);workspace.value.leftTab='solutions'}
const solveError=ref(''),solveStarted=ref(0),elapsedSeconds=ref(0)
const taskTimeout=computed(()=>Number(configForm.algorithmDefaults.planning?.taskTimeoutSeconds||300))
let solveSerial=0,solveController,elapsedTimer
function abandonSolve(){solveSerial++;solveController?.abort();clearInterval(elapsedTimer);if(solveStarted.value)busy.value=false;solveStarted.value=0;solveError.value=''}
watch(()=>[fieldId.value,boreId.value,versionId.value],abandonSolve)
onBeforeUnmount(abandonSolve)
async function solve(){
  if(busy.value)return
  if(pendingNeighborConfirmation.value){ElMessage.warning('请先保存确认所选邻井关系');return}
  if(!detail.value||!versionId.value){ElMessage.warning('请选择已复核的实钻轨迹版本');return}
  if(!form.north&&!form.east&&!form.tvd){ElMessage.warning('请配置轨迹终点');return}
  const request=++solveSerial,field=fieldId.value,version=versionId.value
  solveController=new AbortController();busy.value=true;solveError.value='';solveStarted.value=Date.now();elapsedSeconds.value=0
  elapsedTimer=setInterval(()=>{elapsedSeconds.value=Math.floor((Date.now()-solveStarted.value)/1000)},1000)
  try{
    const defaults=configForm.algorithmDefaults||{},planning=defaults.planning||{}
    const obstacles=selectedNeighbors.value.map(n=>({well_id:n.well_id,survey_version_id:n.survey_version_id}))
    const nativeOptions={...(planning.nativeOptions||{}),trajectory_mode:nativeTrajectoryMode.value,n_segments:Number(engineSegmentCount.value)}
    const payload={field_id:fieldId.value,well_id:detail.value.well_id,current_survey_version_id:versionId.value,
      target:{kind:'POINT',north:Number(form.north),east:Number(form.east),tvd:Number(form.tvd),tolerance_north:Number(form.targetTolerance),tolerance_east:Number(form.targetTolerance),tolerance_tvd:Number(form.targetTolerance)},
      solver:form.solver,constraint_mode:constraintMode.value,
      minimum_sf:Number(form.minimumSf),target_tolerance_m:Number(form.targetTolerance),
      target_inclination:form.constrainAttitude?form.targetInclination:null,target_azimuth:form.constrainAttitude?form.targetAzimuth:null,
      collision_constraint:'SEPARATION_FACTOR',max_iterations:Number(form.maxIterations),segment_bounds:boundsPayload(),
      native_options:nativeOptions,obstacle_versions:obstacles}
    if(constraintMode.value==='GLOBAL')Object.assign(payload,{max_dogleg_deg_per_30m:Number(form.maxDogleg),min_dogleg_deg_per_30m:Number(form.minDogleg),min_segment_length:Number(form.minSegmentLength),max_segment_length:Number(form.maxSegmentLength)})
    const response=await solveTrajectoryPlan(payload,solveController.signal)
    if(request!==solveSerial||field!==fieldId.value||version!==versionId.value)return
    result.value=response
    // Results already have a durable task record. Save only the small input
    // draft; a second large workspace write must not keep the solve spinner on.
    save({params:{...form,constraint_mode:constraintMode.value,engine_trajectory_mode:nativeTrajectoryMode.value,engine_segment_count:engineSegmentCount.value},segmentConstraints:segmentConstraints.value,inputVersions:[versionId.value,...obstacles.map(x=>x.survey_version_id)]}).catch(()=>ElMessage.warning('规划结果已保存到任务历史，工作区草稿保存失败'))
    ElMessage[result.value.status==='SUCCESS'?'success':'warning'](result.value.status==='SUCCESS'?'规划方案已生成':'方案未通过约束审计，请复核结果')
  }catch(e){if(request===solveSerial&&e.code!=='ERR_CANCELED')solveError.value=e.message||'规划失败'}finally{if(request===solveSerial){clearInterval(elapsedTimer);solveStarted.value=0;busy.value=false}}
}
async function saveDraft(){try{await save({params:{...form,engine_trajectory_mode:nativeTrajectoryMode.value,engine_segment_count:engineSegmentCount.value},segmentConstraints:segmentConstraints.value})}catch(e){ElMessage.error(e.message||'保存失败')}}
async function renameDraft(){await rename()}
async function exportBundle(format='bundle',sections){if(!result.value)return;try{if(['witsml','csv'].includes(format)){const b=await exportSurvey({field_id:fieldId.value,stations:result.value.trajectory||[],format});downloadBlob(b,`trajectory-plan.${format}`)}else{const b=await exportDrillingReport({field_id:fieldId.value,title:title.value,format,payload:{...result.value,report_sections:sections,input_versions:[versionId.value,...selectedNeighbors.value.map(n=>n.survey_version_id)]}});downloadBlob(b,`trajectory-plan-report.${format==='bundle'?'zip':format}`)}}catch(e){ElMessage.error(e.message||'导出失败')}}
watch(()=>configForm.algorithmDefaults.planning,p=>{if(!p)return;Object.assign(form,{solver:p.solver||'SLSQP',minSegmentLength:p.minSegmentLength??3,maxSegmentLength:p.maxSegmentLength??30,minDogleg:p.minDoglegDegPer30m??0,maxDogleg:p.maxDoglegDegPer30m??6,minimumSf:p.minimumSf??1.5,targetTolerance:p.targetToleranceM??.5,maxIterations:p.maxIterations??500});constraintMode.value=p.constraintMode||'GLOBAL';nativeTrajectoryMode.value=p.nativeOptions?.trajectory_mode||'arc-straight';nativeSegmentCount.value=Number(p.nativeOptions?.n_segments||3);segmentConstraints.value=JSON.parse(JSON.stringify(p.segmentBounds||[]));if(p.target)Object.assign(form,{north:p.target.north,east:p.target.east,tvd:p.target.tvd,targetInclination:p.target.inclination,targetAzimuth:p.target.azimuth,constrainAttitude:p.target.inclination!=null||p.target.azimuth!=null})},{immediate:true})
watch(design,d=>{if(d?.stations?.length&&!configForm.algorithmDefaults.planning.target){const end=d.stations.at(-1);Object.assign(form,{north:end.north,east:end.east,tvd:end.tvd,targetInclination:end.inclination,targetAzimuth:end.azimuth})}})
watch(trajectoryModes,rows=>{if(!rows.includes(nativeTrajectoryMode.value))nativeTrajectoryMode.value=rows[0]},{immediate:true})
watch(segmentCounts,rows=>{if(!rows.includes(nativeSegmentCount.value))nativeSegmentCount.value=rows[0]},{immediate:true})
watch(engineSegmentCount,n=>{segmentConstraints.value=segmentConstraints.value.filter(r=>r.segment<=n)},{immediate:true})
watch(constraintMode,mode=>{if(mode==='GLOBAL'){segmentConstraints.value=[];return}if(!segmentConstraints.value.length)for(let i=1;i<=engineSegmentCount.value;i++)segmentConstraints.value.push({segment:i,minLength:form.minSegmentLength,maxLength:form.maxSegmentLength,minDls:form.minDogleg,maxDls:form.maxDogleg})})
watch(task.workspaceDraft,d=>{if(d?.params){Object.assign(form,d.params);constraintMode.value=d.params.constraint_mode||constraintMode.value;nativeTrajectoryMode.value=d.params.engine_trajectory_mode||nativeTrajectoryMode.value;nativeSegmentCount.value=Number(d.params.engine_segment_count||nativeSegmentCount.value)}if(d?.segmentConstraints)segmentConstraints.value=d.segmentConstraints},{deep:true})
watch(()=>catalog.value.algorithms,rows=>{if(rows?.length&&!rows.includes(form.solver))form.solver=rows[0]},{immediate:true});engineCatalog().then(v=>catalog.value=v).catch(()=>{})
</script>
<style scoped>
.segment-bound{padding:8px;border:1px solid var(--el-border-color-light);margin-bottom:10px}.workspace-wrapper{min-width:0}.task-form{padding:8px 0}.task-form :deep(.el-form-item){margin-bottom:12px}.three-fields,.two-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px}.two-fields{grid-template-columns:repeat(2,minmax(0,1fr))}.three-fields :deep(.el-input-number),.two-fields :deep(.el-input-number){width:100%}.section-title{font-weight:600;color:var(--el-text-color-primary);border-top:1px solid var(--el-border-color-light);padding-top:10px;margin:6px 0 8px}.field-help{font-size:11px;color:var(--el-text-color-secondary);margin-top:3px}.start-state{display:grid;gap:4px;padding:9px;margin-bottom:10px;border:1px solid var(--el-border-color-light);background:var(--el-fill-color-light);font-size:12px;color:var(--el-text-color-regular)}.start-state b{color:var(--el-text-color-primary)}.source-tag{margin-top:12px}.solution-descriptions :deep(.el-descriptions__content),.solution-descriptions :deep(.el-descriptions__label){word-break:break-word}@media(max-width:700px){.three-fields,.two-fields{grid-template-columns:1fr}}
</style>
