<template>
  <DrillingPageFrame title="轨迹再规划">
    <el-alert v-if="task.dataError.value" :title="task.dataError.value" type="warning" :closable="false" />
    <EngineeringWorkspace ref="workspace" task-type="replanning"
      :title="title"
      :well-label="boreLabel"
      :datum="datum"
      :reference-settings="referenceSettings"
      :has-result="!!result"
      :trajectories="planTrajectories"
      :original-trajectories="originalTrajectories"
      :show-original="true"
      :show-survey-program="true"
      :risk-segments="riskSegments"
      :risk-label="riskLabel"
      :minimum-sf="minimumSf"
      :minimum-centerline-distance="displayResult?.solver_metadata?.minimum_centerline_distance_m ?? displayResult?.constraint_audit?.minimum_centerline_distance_m"
      @save="saveDraft"
      @rename="renameDraft"
      @history="openHistory"
      @export="exportBundle($event?.format||'bundle', $event?.sections)"
      @import="openSurveyImport"
      @connection-portal="openConnectionPortal"
      @generate-report="generateReport"
      @reference-change="saveReferenceSettings"
      @refresh="task.loadBores"
    >
      <template #input>
        <el-alert v-if="realtimeAlert" title="已从实时防碰预警进入绕障再规划" :description="realtimeAlertDescription" type="warning" show-icon :closable="false" />
        <el-form label-position="top" class="task-form"><el-collapse v-model="inputSections"><el-collapse-item title="井身信息" name="wellInfo">
          <el-form-item label="井型"><el-radio-group v-model="form.wellType"><el-radio value="VERTICAL">垂直井</el-radio><el-radio value="DIRECTIONAL">定向井</el-radio></el-radio-group></el-form-item>
          <el-form-item label="规划起算点"><el-radio-group v-model="form.startFrom"><el-radio value="SURFACE">地面位置</el-radio><el-radio value="LAST_SURVEY">最后测斜点</el-radio></el-radio-group></el-form-item>
          <div class="field-help">地面位置使用当前测斜版本的首测点；最后测斜点使用当前版本的末测点。</div>
        </el-collapse-item><el-collapse-item title="井眼与测斜版本" name="well">
          <el-form-item label="正钻井眼"><el-cascader v-model="task.borePath.value" :options="task.wellboreOptions.value" :props="{emitPath:true,checkStrictly:false}" :show-all-levels="true" filterable clearable placeholder="区块 / 井场 / 井 / 井眼" style="width:100%" /></el-form-item>
          <el-form-item label="当前实钻版本"><el-select v-model="versionId" filterable style="width:100%" @change="task.loadDetail"><el-option v-for="v in versions" :key="v.survey_version_id" :value="v.survey_version_id" :label="`v${v.version_no} · MD ${num(v.max_md)} m`" /></el-select></el-form-item>
          <div v-if="planningStart" class="start-state"><b>{{ form.startFrom==='SURFACE'?'地面起算点':'最后测斜点' }} · MD {{ num(planningStart.md) }} m</b><span>INC {{ num(planningStart.inclination) }}° · AZI {{ num(planningStart.azimuth) }}°</span><small>N {{ num(planningStart.north) }} · E {{ num(planningStart.east) }} · TVD {{ num(planningStart.tvd) }}</small></div>
          </el-collapse-item><el-collapse-item title="邻井与防碰规则" name="neighbors"><el-form-item label="防碰约束"><el-tag type="success">分离系数 + 中心距联合约束</el-tag></el-form-item><div class="two-fields"><el-form-item label="工程配置 SF 下限"><el-input-number v-model="form.minimumSf" :min="0.01" :step="0.1" disabled /></el-form-item><el-form-item label="工程配置最小中心距（m）"><el-input-number v-model="form.centerlineMinDistance" :min="0.1" :step="0.1" disabled /></el-form-item></div><div class="field-help">两项阈值由工程配置统一管理，规划任务只读取当前确认的工程统一配置值，并同时作为硬约束。</div><el-form-item label="参与规划的邻井"><NeighborSelector :field-id="fieldId" v-model="neighbors" @change="task.loadNeighborDetails" @save="saveNeighbors" /></el-form-item>
          </el-collapse-item><el-collapse-item title="Survey Program（测斜程序）" name="surveyProgram"><div class="survey-program-meta"><span>当前版本：{{ currentVersionLabel }}</span><span>来源：{{ currentVersion?.source_format || '数据库测斜版本' }}</span></div><el-table :data="surveyProgramRows" border size="small" empty-text="原文未提供测斜程序"><el-table-column type="index" label="序号" width="58"/><el-table-column prop="from_md" label="起始 MD（m）" width="120"/><el-table-column prop="to_md" label="终止 MD（m）" width="120"/><el-table-column prop="survey" label="测量记录" min-width="150"/><el-table-column prop="tool_name" label="工具名称" min-width="160"/><el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip/></el-table><div class="field-help">本表只展示附件解析出的原文内容；原文没有提供的字段不自动补写。</div></el-collapse-item><el-collapse-item title="求解设置" name="solver"><el-form-item label="求解器"><el-select v-model="form.solver" style="width:100%"><el-option v-for="s in solverOptions" :key="s" :label="solverLabel(s)" :value="s" /></el-select></el-form-item>
          <div class="two-fields"><el-form-item label="轨迹段组合"><el-select v-model="nativeTrajectoryMode" style="width:100%"><el-option v-for="mode in trajectoryModes" :key="mode" :label="trajectoryModeLabel(mode)" :value="mode" /></el-select></el-form-item><el-form-item label="轨迹段数"><el-select v-model="nativeSegmentCount" style="width:100%"><el-option v-for="count in segmentCounts" :key="count" :label="`${count} 段（${segmentSequence(count)}）`" :value="count" /></el-select></el-form-item></div>
          <el-form-item label="圆弧段计算模型"><el-select v-model="nativeArcSegmentModel" style="width:100%"><el-option v-for="model in arcSegmentModels" :key="model" :label="arcSegmentModelLabel(model)" :value="model" /></el-select></el-form-item>
          <el-form-item label="最大迭代次数"><el-input-number v-model="form.maxIterations" :min="1" :max="5000" /></el-form-item></el-collapse-item><el-collapse-item title="目标点与终端姿态" name="target">
          <div class="three-fields"><el-form-item label="North（m）"><el-input-number v-model="form.north" :controls="false" /></el-form-item><el-form-item label="East（m）"><el-input-number v-model="form.east" :controls="false" /></el-form-item><el-form-item label="TVD（m）"><el-input-number v-model="form.tvd" :controls="false" /></el-form-item></div>
          <el-form-item label="目标容差（m）"><el-input-number v-model="form.targetTolerance" :min="0.01" :step="0.1" /></el-form-item>
          <el-checkbox v-model="form.constrainAttitude">约束终端井斜与方位</el-checkbox><div v-if="form.constrainAttitude" class="two-fields"><el-form-item label="目标井斜（°）"><el-input-number v-model="form.targetInclination" :min="0.001" :max="179.999" /></el-form-item><el-form-item label="目标方位（°）"><el-input-number v-model="form.targetAzimuth" :min="0" :max="359.999" /></el-form-item></div></el-collapse-item><el-collapse-item title="段长与曲率约束" name="bounds">
          <SegmentConstraints v-model="segmentConstraints" v-model:mode="constraintMode" :trajectory-mode="nativeTrajectoryMode" :count="engineSegmentCount" v-model:min-length="form.minSegmentLength" v-model:max-length="form.maxSegmentLength" v-model:min-dls="form.minDogleg" v-model:max-dls="form.maxDogleg" />
          </el-collapse-item>
        </el-collapse>
          <div v-if="pendingNeighborConfirmation" class="field-help">邻井关系未保存</div><el-button type="primary" :loading="busy" :disabled="pendingNeighborConfirmation||!detail||detail.quality_status!=='READY'" style="width:100%;margin-top:12px" @click="solve">生成规划方案</el-button>
        <p v-if="busy && solveStarted" class="field-help" role="status">正在求解并复核，已用 {{ elapsedSeconds }} 秒；最长等待 {{ taskTimeout }} 秒，算法收敛或达到迭代/函数计算上限后返回。</p><el-alert v-if="solveError" :title="solveError" type="error" :closable="false" style="margin-top:10px" />
        </el-form>
      </template>
      <template #solutions>
          <el-descriptions :column="1" border class="solution-descriptions">
          <el-descriptions-item label="状态">{{ resultState(displayResult?.status) }}</el-descriptions-item><el-descriptions-item label="收敛状态">{{ displayResult ? (displayResult.solver_converged ? '已收敛' : displayResult.feasible_solution ? '可行但未收敛' : '未得到可行解') : '—' }}</el-descriptions-item><el-descriptions-item label="停止原因">{{ stopReason(displayResult?.stop_reason) }}</el-descriptions-item><el-descriptions-item label="求解器">{{ solverLabel(displayResult?.solver_metadata?.actual_solver || displayResult?.solver || form.solver) }}</el-descriptions-item><el-descriptions-item label="轨迹段组合">{{ trajectoryModeLabel(displayResult?.solver_metadata?.configuration?.trajectory_mode) || '—' }}</el-descriptions-item><el-descriptions-item label="圆弧段计算模型">{{ arcSegmentModelLabel(displayResult?.solver_metadata?.configuration?.arc_segment_model || nativeArcSegmentModel) }}</el-descriptions-item><el-descriptions-item label="轨迹段数">{{ displayResult?.segments?.length || '—' }}</el-descriptions-item><el-descriptions-item label="目标函数">轨迹总长度最小化</el-descriptions-item><el-descriptions-item label="轨迹总长">{{ num(displayResult?.total_length) }} m</el-descriptions-item><el-descriptions-item label="终端误差">{{ num(displayResult?.target_error) }} m</el-descriptions-item><el-descriptions-item label="最小 SF">{{ num(minimumSf) }}</el-descriptions-item><el-descriptions-item label="最小中心距">{{ num(displayResult?.solver_metadata?.minimum_centerline_distance_m ?? displayResult?.constraint_audit?.minimum_centerline_distance_m) }} m</el-descriptions-item><el-descriptions-item label="联合约束">分离系数 + 中心距</el-descriptions-item><el-descriptions-item label="迭代次数">{{ displayResult?.iterations ?? '—' }}</el-descriptions-item><el-descriptions-item label="算法执行耗时">{{ algorithmSeconds == null ? '—' : `${num(algorithmSeconds)} s` }}</el-descriptions-item><el-descriptions-item label="网关总耗时">{{ gatewaySeconds == null ? '—' : `${num(gatewaySeconds)} s` }}</el-descriptions-item><el-descriptions-item label="点击到结果返回">{{ clientSeconds == null ? '—' : `${num(clientSeconds)} s` }}</el-descriptions-item><el-descriptions-item label="约束审计">{{ displayResult ? (displayResult.constraint_violations?.length ? displayResult.constraint_violations.join('；') : '通过') : '—' }}</el-descriptions-item>
        </el-descriptions>

        <el-collapse class="segment-results"><el-collapse-item v-for="segment in displayResult?.segments||[]" :key="segment.segment" :title="`第 ${segment.segment} 段求解参数`"><el-descriptions :column="1" border size="small"><el-descriptions-item label="段型">{{segmentTypeLabel(segment.type)}}</el-descriptions-item><el-descriptions-item label="段长">{{num(segment.length)}} m</el-descriptions-item><el-descriptions-item label="狗腿度">{{num(segment.dogleg)}} °/30m</el-descriptions-item><el-descriptions-item label="起始工具面">{{num(segment.start_toolface_deg??segment.toolface)}} °</el-descriptions-item><el-descriptions-item label="滑动 / 复合建议">{{segment.sliding_ratio==null?'—':num(segment.sliding_ratio*100)}}% / {{segment.composite_ratio==null?'—':num(segment.composite_ratio*100)}}%</el-descriptions-item></el-descriptions></el-collapse-item></el-collapse>
        <el-divider />
        <el-descriptions :column="1" border><el-descriptions-item label="版本输入">{{ inputVersionSummary }}</el-descriptions-item><el-descriptions-item label="数据来源">{{ currentVersion?.source_format || '数据库测斜版本' }}</el-descriptions-item></el-descriptions>
      </template>
      <template #collision><CollisionResults :network="displayResult?.collision_evaluation" :neighbors="neighbors" /><el-empty v-if="!displayResult?.collision_evaluation" :description="displayResult?'当前方案未选有效邻井，无防碰扫描结果':'生成方案后显示防碰扫描结果'" /></template>
      <template #table>
        <div class="drilling-toolbar"><el-select v-model="selectedTablePlan" style="width:260px" placeholder="选择求解结果" @change="switchTablePlan"><el-option v-for="item in tablePlanOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-button :disabled="!tableDisplayResult?.trajectory?.length" icon="Download" @click="exportTablePlan('csv')">导出 CSV</el-button><el-button :disabled="!tableDisplayResult?.trajectory?.length" icon="Download" @click="exportTablePlan('witsml')">导出 WITSML</el-button></div>
          <el-table :data="tableRows" height="100%" empty-text="生成方案后显示轨迹设计表"><el-table-column prop="sequence" label="序号" width="64"/><el-table-column prop="md" label="MD（m）" min-width="100"/><el-table-column prop="north" label="North（m）" min-width="110"/><el-table-column prop="east" label="East（m）" min-width="110"/><el-table-column prop="tvd" label="TVD（m）" min-width="100"/><el-table-column prop="inclination" label="井斜（°）" min-width="95"/><el-table-column prop="azimuth" label="方位（°）" min-width="95"/><el-table-column prop="dogleg" label="狗腿度（°/30m）" min-width="135"/><el-table-column prop="toolface" label="工具面（°）" min-width="105"/><el-table-column prop="slide" label="滑动比例" min-width="95"/><el-table-column prop="composite" label="复合比例" min-width="95"/><el-table-column prop="constraint" label="约束状态" min-width="90"><template #default="{row}">{{({PASS:'通过',FAIL:'未通过',FAILED:'未通过',VIOLATED:'未通过'}[row.constraint]||row.constraint)}}</template></el-table-column></el-table>
      </template>
      <template #context>
        <el-descriptions :column="1" border><el-descriptions-item label="工程路径">{{ boreLabel || '—' }}</el-descriptions-item><el-descriptions-item label="实钻版本">{{ currentVersionLabel }}</el-descriptions-item><el-descriptions-item label="最新 MD">{{ num(lastSurvey?.md) }} m</el-descriptions-item><el-descriptions-item label="数据时间">{{ lastSurvey?.recorded_at || lastSurvey?.survey_time || '—' }}</el-descriptions-item></el-descriptions>
        <el-tag :type="lastSurvey ? 'success' : 'info'" class="source-tag">{{ lastSurvey ? '已加载实钻测斜版本' : '尚未加载测斜版本' }}</el-tag>
      </template>
      <template #original><div class="workspace-scroll original-table"><div class="table-caption">原始轨道设计表{{ design?.version_no ? ` · v${design.version_no}` : '' }}</div><el-table :data="originalRows" height="100%" empty-text="暂无原始轨道设计表"><el-table-column prop="sequence" label="序号" width="64"/><el-table-column prop="md" label="MD（m）" min-width="100"/><el-table-column prop="north" label="North（m）" min-width="110"/><el-table-column prop="east" label="East（m）" min-width="110"/><el-table-column prop="tvd" label="TVD（m）" min-width="100"/><el-table-column prop="inclination" label="井斜（°）" min-width="95"/><el-table-column prop="azimuth" label="方位（°）" min-width="95"/><el-table-column prop="dogleg" label="狗腿度（°/30m）" min-width="135"/></el-table></div></template>
      <template #survey><div class="survey-program-page"><el-descriptions :column="2" border title="Survey Report - Geographic（测斜报告）"><el-descriptions-item label="公司">{{ surveyHeader.company || '—' }}</el-descriptions-item><el-descriptions-item label="项目">{{ surveyHeader.project || '—' }}</el-descriptions-item><el-descriptions-item label="井场">{{ surveyHeader.site || currentFieldName || '—' }}</el-descriptions-item><el-descriptions-item label="井">{{ surveyHeader.well || bore?.well_name || '—' }}</el-descriptions-item><el-descriptions-item label="井眼">{{ surveyHeader.wellbore || boreLabel || '—' }}</el-descriptions-item><el-descriptions-item label="设计">{{ surveyHeader.design || '—' }}</el-descriptions-item><el-descriptions-item label="本地坐标参考">{{ surveyHeader.coordinate || '—' }}</el-descriptions-item><el-descriptions-item label="TVD 基准">{{ surveyHeader.tvdReference || '—' }}</el-descriptions-item><el-descriptions-item label="MD 基准">{{ surveyHeader.mdReference || '—' }}</el-descriptions-item><el-descriptions-item label="北向参考">{{ surveyHeader.northReference || '—' }}</el-descriptions-item><el-descriptions-item label="测斜计算方法">{{ surveyHeader.calculationMethod || '—' }}</el-descriptions-item><el-descriptions-item label="数据库">{{ surveyHeader.database || '—' }}</el-descriptions-item></el-descriptions><div class="table-caption">测斜工具分段 / Survey Program <span v-if="surveyHeader.surveyProgramDate">· 日期 {{ surveyHeader.surveyProgramDate }}</span></div><el-table :data="surveyProgramRows" border empty-text="原文未提供测斜程序"><el-table-column type="index" label="序号" width="64"/><el-table-column prop="from_md" label="起始井深（m）" width="130"/><el-table-column prop="to_md" label="终止井深（m）" width="130"/><el-table-column prop="survey" label="测量记录（井眼）" min-width="190"/><el-table-column prop="tool_name" label="工具名称" min-width="180"/><el-table-column prop="description" label="说明" min-width="280" show-overflow-tooltip/></el-table><div class="field-help">字段顺序和内容按附件原始 Survey Report 表格组织；没有原文证据的字段保持空值。</div></div></template>
      <template #bottom><span>规划方案与原始方案、邻井轨迹均来自已选版本。</span></template>
    </EngineeringWorkspace>
    <el-drawer v-model="historyOpen" title="再规划任务历史" size="520px"><el-table :data="visibleHistory" empty-text="暂无历史任务"><el-table-column prop="record_id" label="任务" width="90"/><el-table-column prop="status" label="状态"/><el-table-column prop="created_at" label="时间"/><el-table-column label="操作"><template #default="s"><el-button link type="primary" @click="viewHistory(s.row)">查看结果</el-button></template></el-table-column></el-table></el-drawer>
    <ResizableDialog v-model="historyResultOpen" title="再规划结果" width="min(1200px, calc(100vw - 24px))" class="drilling-dialog"><div v-loading="historyResultLoading" class="history-result"><el-alert v-if="historyResultError" :title="historyResultError" type="error" :closable="false"/><template v-if="historyResult"><el-descriptions :column="3" border><el-descriptions-item label="任务">{{ historyResultMeta?.title || '轨迹再规划' }}</el-descriptions-item><el-descriptions-item label="状态">{{ resultState(historyResult.status) }}</el-descriptions-item><el-descriptions-item label="算法">{{ solverLabel(historyResult.selected_solver || historyResult.solver) }}</el-descriptions-item><el-descriptions-item label="收敛">{{ historyResult.solver_converged ? '已收敛' : historyResult.feasible_solution ? '可行但未收敛' : '未得到可行解' }}</el-descriptions-item><el-descriptions-item label="轨迹总长">{{ num(historyResult.total_length) }} m</el-descriptions-item><el-descriptions-item label="终点误差">{{ num(historyResult.target_error) }} m</el-descriptions-item><el-descriptions-item label="最小 SF">{{ num(historyResult.solver_metadata?.minimum_sf ?? historyResult.minimum_sf) }}</el-descriptions-item><el-descriptions-item label="最小中心距">{{ num(historyResult.solver_metadata?.minimum_centerline_distance_m ?? historyResult.constraint_audit?.minimum_centerline_distance_m) }} m</el-descriptions-item><el-descriptions-item label="迭代次数">{{ historyResult.iterations ?? '—' }}</el-descriptions-item><el-descriptions-item label="停止原因">{{ stopReason(historyResult.stop_reason) }}</el-descriptions-item><el-descriptions-item label="算法耗时">{{ num(historyResult.timing?.execution_total_seconds ?? historyResult.elapsed_seconds) }} s</el-descriptions-item><el-descriptions-item label="网关总耗时">{{ num(historyResult.timing?.gateway_total_seconds) }} s</el-descriptions-item><el-descriptions-item label="约束审计">{{ historyResult.constraint_violations?.length ? historyResult.constraint_violations.join('；') : '通过' }}</el-descriptions-item></el-descriptions><el-divider content-position="left">分段参数</el-divider><el-table v-if="historyResult.segments?.length" :data="historyResult.segments" border size="small"><el-table-column prop="segment" label="段号" width="80"/><el-table-column prop="type" label="段型"/><el-table-column prop="length" label="段长（m）"><template #default="{row}">{{ num(row.length ?? row.segment_length) }}</template></el-table-column><el-table-column prop="dogleg" label="狗腿度（°/30m）"><template #default="{row}">{{ num(row.dogleg ?? row.dogleg_deg_per_30m) }}</template></el-table-column><el-table-column prop="start_md" label="起始 MD（m）"><template #default="{row}">{{ num(row.start_md) }}</template></el-table-column><el-table-column prop="end_md" label="结束 MD（m）"><template #default="{row}">{{ num(row.end_md) }}</template></el-table-column></el-table><el-empty v-else description="暂无分段结果" :image-size="48"/><el-divider content-position="left">轨道表</el-divider><el-table :data="historyRows" border max-height="420"><el-table-column prop="sequence" label="序号" width="64"/><el-table-column prop="md" label="MD（m）"/><el-table-column prop="north" label="North（m）"/><el-table-column prop="east" label="East（m）"/><el-table-column prop="tvd" label="TVD（m）"/><el-table-column prop="inclination" label="井斜（°）"/><el-table-column prop="azimuth" label="方位（°）"/><el-table-column prop="dogleg" label="狗腿度（°/30m）"/></el-table></template></div><template #footer><el-button @click="historyResultOpen=false">关闭</el-button><el-button type="primary" @click="switchHistoryTable">切换轨道表</el-button></template></ResizableDialog>
  </DrillingPageFrame>
</template>
<script setup>
import {trajectoryIdentity} from '@/utils/wellboreHierarchy'
import {computed,reactive,ref,watch,onBeforeUnmount} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {solveTrajectoryPlan,exportSurvey,exportDrillingReport} from '@/api/drilling'
import {taskHistory,taskHistoryDetail} from '@/api/drillingRegistry'
import DrillingPageFrame from '../../layout/DrillingPageFrame.vue'
import EngineeringWorkspace from '../../layout/EngineeringWorkspace.vue'
import SegmentConstraints from '../../layout/SegmentConstraints.vue'
import {segmentBoundsPayload} from '@/utils/segmentConstraints'
import NeighborSelector from '../../layout/NeighborSelector.vue'
import CollisionResults from '../../layout/CollisionResults.vue'
import {useTrajectoryTask} from '../../composables/useTrajectoryTask'
import {engineCatalog} from '@/api/drillingRegistry'
import {downloadBlob} from '@/api/drillingRegistry'
import ResizableDialog from '@/components/ResizableDialog.vue'
import {trajectoryColors} from '@/utils/trajectoryColors'
const route=useRoute(),router=useRouter(),task=useTrajectoryTask('replanning'),{fieldId,currentFieldName,configForm,bores,boreId,bore,versions,versionId,detail,design,neighbors,selectedNeighbors,baseTrajectories,title,result,busy,save,rename,showHistory,historyOpen,history,label,saveNeighbors}=task
const workspace=ref(),inputSections=ref(['wellInfo','well','surveyProgram','target','bounds']),constraintMode=ref('GLOBAL'),segmentConstraints=ref([]),catalog=ref({algorithms:['SLSQP','trust-constr','COBYLA','COBYQA'],trajectory_modes:['arc','arc-straight','straight-arc'],segment_counts:[1,2,3,4,5]}),nativeTrajectoryMode=ref('arc-straight'),nativeArcSegmentModel=ref('minimum_curvature'),nativeSegmentCount=ref(3),form=reactive({solver:'ALL',north:0,east:0,tvd:0,targetTolerance:.5,minSegmentLength:3,maxSegmentLength:30,minDogleg:0,maxDogleg:6,minimumSf:1.5,centerlineMinDistance:5,maxIterations:500,constrainAttitude:false,targetInclination:1,targetAzimuth:0,wellType:'DIRECTIONAL',startFrom:'LAST_SURVEY',rkbElevation:null,depthDatum:'',mdDatum:'',northReference:'GRID_NORTH',gridConvergence:null})
// Geometry and segment count are native engine capabilities, not business
// Geometry modes and segment counts come from the supplied native engine.
const trajectoryModes=computed(()=>catalog.value.trajectory_modes||['arc','arc-straight','straight-arc']),arcSegmentModels=computed(()=>catalog.value.arc_segment_models||['minimum_curvature','constant_toolface']),segmentCounts=computed(()=>catalog.value.segment_counts||[1,2,3,4,5]),engineSegmentCount=computed(()=>Number(nativeSegmentCount.value||3)),solverOptions=computed(()=>['ALL',...(catalog.value.algorithms||['SLSQP','trust-constr','COBYLA','COBYQA']).filter(s=>['SLSQP','trust-constr','COBYLA','COBYQA'].includes(s))].filter((s,i,a)=>a.indexOf(s)===i))
const resultState=value=>({SUCCESS:'规划完成',FEASIBLE_NOT_CONVERGED:'得到可行方案',TIMEOUT:'达到最长等待时间',INFEASIBLE:'未得到可行方案',FAILED:'执行失败'}[value]||value||'未生成')
const stopReason=value=>({CONVERGED:'算法收敛',ITERATION_LIMIT:'达到迭代次数上限',FUNCTION_EVALUATION_LIMIT:'达到函数计算次数上限',MAX_WAIT_SECONDS:'达到最长等待时间',FEASIBLE_NOT_CONVERGED:'得到可行解，尚未收敛',NO_FEASIBLE_SOLUTION:'未得到可行解'}[value]||value||'—')
const segmentTypeLabel=value=>({arc:'圆弧',straight:'直线',ARC:'圆弧',STRAIGHT:'直线'}[value]||value||'—')
const solverLabels={ALL:'全部并行（SLSQP、trust-constr、COBYLA、COBYQA）',SLSQP:'SLSQP','trust-constr':'trust-constr',COBYLA:'COBYLA',COBYQA:'COBYQA'}
const solverLabel=value=>solverLabels[value]||value||'—'
const trajectoryModeLabel=mode=>({arc:'圆弧','arc-straight':'圆弧—直线','straight-arc':'直线—圆弧'}[mode]||mode)
const arcSegmentModelLabel=model=>({minimum_curvature:'最小曲率平面圆弧',constant_toolface:'恒工具面恒曲率空间曲线'}[model]||model)
const segmentSequence=(count)=>{
  const n=Number(count||1)
  if(nativeTrajectoryMode.value==='arc') return Array.from({length:n},()=> '圆弧').join('—')
  const first=nativeTrajectoryMode.value==='straight-arc'?'直线':'圆弧'
  const other=first==='直线'?'圆弧':'直线'
  return Array.from({length:n},(_,index)=>index%2===0?first:other).join('—')
}
const realtimeAlert=computed(()=>String(route.query.alert||'')==='collision'),realtimeAlertDescription=computed(()=>`预测钻头 MD ${route.query.predictedMd||'—'} m，风险邻井 ${route.query.neighborId||'—'}。请确认终点和分段约束后生成方案。`),lastSurvey=computed(()=>detail.value?.stations?.at(-1)),planningStart=computed(()=>form.startFrom==='SURFACE'?(detail.value?.stations?.[0]||lastSurvey.value):lastSurvey.value),currentVersion=computed(()=>versions.value.find(v=>Number(v.survey_version_id)===Number(versionId.value))),boreLabel=computed(()=>bore.value?label(bore.value):''),datum=computed(()=>detail.value?.reference?.depth_datum||'—'),currentVersionLabel=computed(()=>currentVersion.value?`v${currentVersion.value.version_no} · MD ${num(currentVersion.value.max_md)} m`:'—'),inputVersionSummary=computed(()=>`${versionId.value||'—'} + ${selectedNeighbors.value.length} 个邻井版本`),surveyProgramRows=computed(()=>{const rows=detail.value?.reference?.survey_tool_program||detail.value?.reference?.survey_program||detail.value?.survey_tool_program||[];return Array.isArray(rows)?rows:[]}),surveyHeader=computed(()=>{const ref=detail.value?.reference||{};return {company:ref.company||ref.company_name,project:ref.project||ref.project_name,site:ref.site||ref.site_name,well:ref.well||ref.well_name,wellbore:ref.wellbore||ref.wellbore_name,design:ref.design||ref.design_name,coordinate:ref.coordinate_system_text||ref.local_coordinate_reference,tvdReference:ref.depth_datum||ref.tvd_reference,mdReference:ref.md_datum||ref.md_reference,northReference:ref.north_reference,calculationMethod:ref.survey_calculation_method,database:ref.database,surveyProgramDate:ref.survey_program_date||ref.survey_program_date_text}})
const pendingNeighborConfirmation=computed(()=>neighbors.value.some(n=>n.selection_dirty))
const referenceSettings=computed(()=>({rkbElevation:form.rkbElevation,depthDatum:form.depthDatum||datum.value,mdDatum:form.mdDatum,northReference:form.northReference||'GRID_NORTH',gridConvergence:form.gridConvergence}))
const displayResult=result
const tableDisplayResult=ref(null),selectedTablePlan=ref('latest'),historyResultOpen=ref(false),historyResultLoading=ref(false),historyResultError=ref(''),historyResult=ref(null),historyResultMeta=ref(null),historyResultKey=ref('')
const riskSegments=computed(()=>displayResult.value?.collision_evaluation?.risk_segments||[]),riskLabel=computed(()=>displayResult.value?.collision_evaluation?.operational_risk_level||(displayResult.value?(displayResult.value.feasible_solution?'BLUE':'YELLOW'):'未评价')),minimumSf=computed(()=>displayResult.value?.solver_metadata?.minimum_sf??displayResult.value?.minimum_sf??null)
const originalTrajectories=computed(()=>baseTrajectories.value.filter(t=>t.kind==='DESIGN'||t.kind==='ACTUAL'||t.id!=='planned'))
const planTrajectories=computed(()=>(result.value?.trajectory?.length?[...baseTrajectories.value,{...trajectoryIdentity(task.bore.value),id:'planned',label:'再规划方案',kind:'PLANNED',points:result.value.trajectory,key_mds:(result.value.segments||[]).flatMap(s=>[s.start_md,s.end_md]),color:trajectoryColors.PLANNED}]:baseTrajectories.value))
const visibleHistory=computed(()=>history.value.filter(x=>!detail.value?.well_id||Number(x.well_id)===Number(detail.value.well_id)))
const tablePlanOptions=computed(()=>[{value:'latest',label:'最新求解结果'},...visibleHistory.value.map(x=>({value:x.record_key,label:`${x.title||`任务 #${x.record_id}`} · ${resultState(x.status)}`}))])
const tableRows=computed(()=>{const rows=tableDisplayResult.value?.trajectory||[],segments=tableDisplayResult.value?.segments||[];return rows.map((r,i)=>{const s=segments.find(x=>Number(x.segment)===Number(r.segment))||segments[i-1]||{};return {sequence:i+1,md:num(r.md),north:num(r.north),east:num(r.east),tvd:num(r.tvd),inclination:num(r.inclination),azimuth:num(r.azimuth),dogleg:num(r.dogleg_deg_per_30m??r.dogleg),toolface:num(r.toolface??s.start_toolface_deg??s.toolface),slide:s.sliding_ratio==null?'—':`${(Number(s.sliding_ratio)*100).toFixed(1)}%`,composite:s.composite_ratio==null?'—':`${(Number(s.composite_ratio)*100).toFixed(1)}%`,constraint:s.constraint_status||s.constraintStatus||'通过'}})})
const originalRows=computed(()=>{const rows=design.value?.stations||[];return rows.map((r,i)=>({sequence:i+1,md:num(r.md),north:num(r.north),east:num(r.east),tvd:num(r.tvd),inclination:num(r.inclination),azimuth:num(r.azimuth),dogleg:num(r.dogleg_deg_per_30m??r.dogleg)}))})
const historyRows=computed(()=>{const rows=historyResult.value?.trajectory||[];return rows.map((r,i)=>({sequence:i+1,md:num(r.md),north:num(r.north),east:num(r.east),tvd:num(r.tvd),inclination:num(r.inclination),azimuth:num(r.azimuth),dogleg:num(r.dogleg_deg_per_30m??r.dogleg)}))})
function num(v){return v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v))?Number(v).toFixed(2):'—'}
function boundsPayload(){return constraintMode.value==='GLOBAL'?[]:segmentBoundsPayload(nativeTrajectoryMode.value,engineSegmentCount.value,segmentConstraints.value)}
function parseHistoryValue(value){if(typeof value!=='string')return value;try{return JSON.parse(value)}catch{return null}}
async function openHistory(){await showHistory();selectedTablePlan.value='latest'}
async function loadHistoryResult(row,open=true){
  if(!row?.record_key||!fieldId.value)return
  historyResultLoading.value=true;historyResultError.value='';historyResultKey.value=row.record_key;historyResultMeta.value=row
  try{
    const stored=await taskHistoryDetail(fieldId.value,row.record_key)
    const record=stored?.record||{},data=parseHistoryValue(record.result_json||record.result_summary_json)
    if(!data||typeof data!=='object')throw Error('此任务没有保存可展示的轨道结果')
    historyResult.value=data;tableDisplayResult.value=data;selectedTablePlan.value=row.record_key
    if(open){historyOpen.value=false;historyResultOpen.value=true}
  }catch(e){historyResultError.value=e.message||'历史结果读取失败';if(open)historyResultOpen.value=true}
  finally{historyResultLoading.value=false}
}
async function viewHistory(row){await loadHistoryResult(row,true)}
function switchHistoryTable(){historyResultOpen.value=false;selectedTablePlan.value=historyResultKey.value;tableDisplayResult.value=historyResult.value}
async function switchTablePlan(key){if(key==='latest'){historyResultKey.value='';tableDisplayResult.value=result.value;return}const row=history.value.find(item=>String(item.record_key)===String(key));if(row)await loadHistoryResult(row,false)}
function exportTablePlan(format='csv'){const value=tableDisplayResult.value;if(!value?.trajectory?.length)return;exportSurvey({field_id:fieldId.value,stations:value.trajectory,format}).then(blob=>downloadBlob(blob,`trajectory-plan-${selectedTablePlan.value}.${format}`)).catch(e=>ElMessage.error(e.message||'导出失败'))}
async function restoreHistory(row){await loadHistoryResult(row,true)}
const solveError=ref(''),solveStarted=ref(0),elapsedSeconds=ref(0),clientSeconds=ref(null)
const taskTimeout=computed(()=>Number(configForm.algorithmDefaults.planning?.realtimeBudgetSeconds||configForm.algorithmDefaults.planning?.taskTimeoutSeconds||30))
let solveSerial=0,solveController,elapsedTimer
function abandonSolve(){clientSeconds.value=null;elapsedSeconds.value=0;solveSerial++;solveController?.abort();clearInterval(elapsedTimer);if(solveStarted.value)busy.value=false;solveStarted.value=0;solveError.value=''}
watch(()=>[fieldId.value,boreId.value,versionId.value],abandonSolve)
onBeforeUnmount(abandonSolve)
async function solve(){
  if(busy.value)return
  const inputIssue=task.inputIssue()
  if(inputIssue){ElMessage.warning(inputIssue);return}
  if(pendingNeighborConfirmation.value){ElMessage.warning('请先保存确认所选邻井关系');return}
  if(!detail.value||!versionId.value){ElMessage.warning('请选择已复核的实钻轨迹版本');return}
  if(!form.north&&!form.east&&!form.tvd){ElMessage.warning('请配置轨迹终点');return}
  const request=++solveSerial,field=fieldId.value,version=versionId.value,clientStarted=performance.now()
  solveController=new AbortController();busy.value=true;solveError.value='';clientSeconds.value=null;solveStarted.value=Date.now();elapsedSeconds.value=0
  elapsedTimer=setInterval(()=>{elapsedSeconds.value=Math.floor((Date.now()-solveStarted.value)/1000)},1000)
  try{
    const defaults=configForm.algorithmDefaults||{},planning=defaults.planning||{}
    const obstacles=selectedNeighbors.value.map(n=>({well_id:n.well_id,survey_version_id:n.survey_version_id}))
    const nativeOptions={...(planning.nativeOptions||{}),trajectory_mode:nativeTrajectoryMode.value,arc_segment_model:nativeArcSegmentModel.value,n_segments:Number(engineSegmentCount.value)}
    const payload={field_id:fieldId.value,well_id:detail.value.well_id,current_survey_version_id:versionId.value,
      start_md:Number(planningStart.value?.md??0),start_north:Number(planningStart.value?.north??0),start_east:Number(planningStart.value?.east??0),start_tvd:Number(planningStart.value?.tvd??0),start_inclination:Number(planningStart.value?.inclination??0),start_azimuth:Number(planningStart.value?.azimuth??0),target:{kind:'POINT',north:Number(form.north),east:Number(form.east),tvd:Number(form.tvd),tolerance_north:Number(form.targetTolerance),tolerance_east:Number(form.targetTolerance),tolerance_tvd:Number(form.targetTolerance)},
      solver:form.solver,constraint_mode:constraintMode.value,
      minimum_sf:Number(form.minimumSf),target_tolerance_m:Number(form.targetTolerance),
      target_inclination:form.constrainAttitude?form.targetInclination:null,target_azimuth:form.constrainAttitude?form.targetAzimuth:null,
      collision_constraint:'SEPARATION_FACTOR_AND_CENTER_DISTANCE',centerline_min_distance_m:Number(form.centerlineMinDistance),max_iterations:Number(form.maxIterations),segment_bounds:boundsPayload(),
      native_options:{...nativeOptions,well_type:form.wellType,start_from:form.startFrom,reference_settings:{rkb_elevation_m:form.rkbElevation,depth_datum:form.depthDatum,md_datum:form.mdDatum,north_reference:form.northReference,grid_convergence_deg:form.gridConvergence}},obstacle_versions:obstacles}
    if(constraintMode.value==='GLOBAL')Object.assign(payload,{max_dogleg_deg_per_30m:Number(form.maxDogleg),min_dogleg_deg_per_30m:Number(form.minDogleg),min_segment_length:Number(form.minSegmentLength),max_segment_length:Number(form.maxSegmentLength)})
    const response=await solveTrajectoryPlan(payload,solveController.signal)
    if(request!==solveSerial||field!==fieldId.value||version!==versionId.value)return
    result.value=response
    tableDisplayResult.value=response
    selectedTablePlan.value='latest'
    clientSeconds.value=(performance.now()-clientStarted)/1000
    // Results already have a durable task record. Save only the small input
    // draft; a second large workspace write must not keep the solve spinner on.
    save({params:{...form,constraint_mode:constraintMode.value,engine_trajectory_mode:nativeTrajectoryMode.value,engine_arc_segment_model:nativeArcSegmentModel.value,engine_segment_count:engineSegmentCount.value},segmentConstraints:segmentConstraints.value,inputVersions:[versionId.value,...obstacles.map(x=>x.survey_version_id)]}).catch(()=>ElMessage.warning('规划结果已保存到任务历史，工作区草稿保存失败'))
    const message=result.value.status==='SUCCESS'?'规划方案已收敛':result.value.status==='FEASIBLE_NOT_CONVERGED'?'已得到可行方案，但算法未收敛':'方案未通过约束审计，请复核结果'
    ElMessage[result.value.status==='SUCCESS'?'success':result.value.status==='FEASIBLE_NOT_CONVERGED'?'warning':'error'](message)
  }catch(e){if(request===solveSerial&&e.code!=='ERR_CANCELED')solveError.value=e.message||'规划失败'}finally{if(request===solveSerial){clearInterval(elapsedTimer);solveStarted.value=0;busy.value=false}}
}
const algorithmSeconds=computed(()=>displayResult.value?.timing?.execution_total_seconds??displayResult.value?.elapsed_seconds??null)
const gatewaySeconds=computed(()=>displayResult.value?.timing?.gateway_total_seconds??null)
watch(result,value=>{if(selectedTablePlan.value==='latest')tableDisplayResult.value=value},{immediate:true})
async function saveDraft(){try{await save({params:{...form,engine_trajectory_mode:nativeTrajectoryMode.value,engine_arc_segment_model:nativeArcSegmentModel.value,engine_segment_count:engineSegmentCount.value},segmentConstraints:segmentConstraints.value})}catch(e){ElMessage.error(e.message||'保存失败')}}
function saveReferenceSettings(value){Object.assign(form,{rkbElevation:value.rkbElevation,depthDatum:value.depthDatum,mdDatum:value.mdDatum,northReference:value.northReference,gridConvergence:value.gridConvergence});saveDraft()}
function openSurveyImport(){router.push({path:'/drilling/basic/surveys',query:{tab:'sources'}})}
function openConnectionPortal(){router.push({path:'/drilling/basic/realtime-data',query:{tab:'connectors',fieldId:fieldId.value,returnTo:'replanning'}})}
function generateReport(){exportBundle('bundle',['trajectory','constraints','collision','sources'])}
async function renameDraft(){await rename()}
async function exportBundle(format='bundle',sections){if(!result.value)return;try{if(['witsml','csv'].includes(format)){const b=await exportSurvey({field_id:fieldId.value,stations:result.value.trajectory||[],format});downloadBlob(b,`trajectory-plan.${format}`)}else{const b=await exportDrillingReport({field_id:fieldId.value,title:title.value,format,payload:{...result.value,report_sections:sections,input_versions:[versionId.value,...selectedNeighbors.value.map(n=>n.survey_version_id)]}});downloadBlob(b,`trajectory-plan-report.${format==='bundle'?'zip':format}`)}}catch(e){ElMessage.error(e.message||'导出失败')}}
watch(()=>configForm.algorithmDefaults.planning,p=>{if(!p)return;Object.assign(form,{solver:p.solver||'ALL',minSegmentLength:p.minSegmentLength??3,maxSegmentLength:p.maxSegmentLength??30,minDogleg:p.minDoglegDegPer30m??0,maxDogleg:p.maxDoglegDegPer30m??6,minimumSf:p.minimumSf??1.5,centerlineMinDistance:p.nativeOptions?.centerline_min_distance_m??5,targetTolerance:p.targetToleranceM??.5,maxIterations:p.maxIterations??500});constraintMode.value=p.constraintMode||'GLOBAL';nativeTrajectoryMode.value=p.nativeOptions?.trajectory_mode||'arc-straight';nativeArcSegmentModel.value=p.nativeOptions?.arc_segment_model||'minimum_curvature';nativeSegmentCount.value=Number(p.nativeOptions?.n_segments||3);segmentConstraints.value=JSON.parse(JSON.stringify(p.segmentBounds||[]));if(p.target)Object.assign(form,{north:p.target.north,east:p.target.east,tvd:p.target.tvd,targetInclination:p.target.inclination,targetAzimuth:p.target.azimuth,constrainAttitude:p.target.inclination!=null||p.target.azimuth!=null})},{immediate:true})
watch(design,d=>{if(d?.stations?.length&&!configForm.algorithmDefaults.planning.target){const end=d.stations.at(-1);Object.assign(form,{north:end.north,east:end.east,tvd:end.tvd,targetInclination:end.inclination,targetAzimuth:end.azimuth})}})
watch(trajectoryModes,rows=>{if(!rows.includes(nativeTrajectoryMode.value))nativeTrajectoryMode.value=rows[0]},{immediate:true})
watch(segmentCounts,rows=>{if(!rows.includes(nativeSegmentCount.value))nativeSegmentCount.value=rows[0]},{immediate:true})
watch(arcSegmentModels,rows=>{if(!rows.includes(nativeArcSegmentModel.value))nativeArcSegmentModel.value=rows[0]},{immediate:true})
watch(task.workspaceDraft,d=>{if(d?.params){Object.assign(form,d.params);constraintMode.value=d.params.constraint_mode||constraintMode.value;nativeTrajectoryMode.value=d.params.engine_trajectory_mode||nativeTrajectoryMode.value;nativeArcSegmentModel.value=d.params.engine_arc_segment_model||nativeArcSegmentModel.value;nativeSegmentCount.value=Number(d.params.engine_segment_count||nativeSegmentCount.value)}if(d?.segmentConstraints)segmentConstraints.value=d.segmentConstraints},{deep:true})
watch(fieldId,async value=>{if(!value)return;try{const response=await taskHistory(value,'REPLANNING');history.value=response?.rows||response?.data?.rows||[]}catch{ /* 历史读取失败不阻断当前新规划 */ }},{immediate:true})
watch(()=>detail.value?.well_id,async well=>{if(!well||result.value||!visibleHistory.value.length)return;await loadHistoryResult(visibleHistory.value[0],false)})
watch(()=>catalog.value.algorithms,rows=>{if(rows?.length&&!solverOptions.value.includes(form.solver))form.solver=rows[0]},{immediate:true});engineCatalog().then(v=>catalog.value=v).catch(()=>{})
</script>
<style scoped>
.segment-bound{padding:8px;border:1px solid var(--el-border-color-light);margin-bottom:10px}.workspace-wrapper{min-width:0}.task-form{padding:8px 0}.task-form :deep(.el-form-item){margin-bottom:12px}.task-form :deep(.el-form-item__label){height:auto;line-height:1.5;white-space:normal}.three-fields,.two-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px}.two-fields{grid-template-columns:repeat(2,minmax(0,1fr))}.three-fields :deep(.el-input-number),.two-fields :deep(.el-input-number){width:100%}.section-title{font-weight:600;color:var(--el-text-color-primary);border-top:1px solid var(--el-border-color-light);padding-top:10px;margin:6px 0 8px}.field-help{font-size:11px;color:var(--el-text-color-secondary);margin-top:3px}.start-state{display:grid;gap:4px;padding:9px;margin-bottom:10px;border:1px solid var(--el-border-color-light);background:var(--el-fill-color-light);font-size:12px;color:var(--el-text-color-regular)}.start-state b{color:var(--el-text-color-primary)}.source-tag{margin-top:12px}.solution-descriptions :deep(.el-descriptions__content),.solution-descriptions :deep(.el-descriptions__label){word-break:break-word}@media(max-width:700px){.three-fields,.two-fields{grid-template-columns:1fr}}
.algorithm-runs{margin-top:12px}.history-result{min-height:420px}.history-result :deep(.el-table){margin-bottom:12px}.original-table{height:100%}.table-caption{padding:8px 0;font-weight:600;color:var(--el-text-color-primary)}.survey-program-meta{display:flex;gap:16px;flex-wrap:wrap;margin:0 0 10px;color:var(--el-text-color-secondary);font-size:12px}.survey-program-page{min-width:760px;padding:4px}.survey-program-page :deep(.el-descriptions){margin-bottom:16px}.survey-program-page :deep(.el-descriptions__title){font-size:14px}.survey-program-page :deep(.el-table){margin-bottom:12px}
</style>
