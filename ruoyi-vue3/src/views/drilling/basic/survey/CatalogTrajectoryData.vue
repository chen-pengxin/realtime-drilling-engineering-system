<template>
  <el-dialog v-model="open" title="轨迹数据版本" width="min(1120px, calc(100vw - 32px))" append-to-body>
    <div v-loading="loading">
      <el-descriptions v-if="detail" :column="3" border class="mb16">
        <el-descriptions-item label="井 / 井眼">{{ selected?.well_code }} / {{ selected?.wellbore_code }}</el-descriptions-item>
        <el-descriptions-item label="数据版本">v{{ selected?.version_no }} · {{ selected?.trajectory_kind === 'DESIGN' ? '设计' : '实钻' }}</el-descriptions-item>
        <el-descriptions-item label="数据快照 ID">{{ selected?.survey_version_id }}</el-descriptions-item>
        <el-descriptions-item label="来源文件">{{ fileDisplayName(selected) }}</el-descriptions-item><el-descriptions-item label="原始文件名">{{ selected?.original_name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="原始表定位">{{ selected?.source_locator || '—' }}</el-descriptions-item>
        <el-descriptions-item label="坐标系">{{ detail.reference?.crs || detail.reference?.coordinate_system || '—' }}</el-descriptions-item>
        <el-descriptions-item label="来源方位基准">{{ northLabel(detail.reference?.source_reference?.north_reference||detail.reference?.source_north_reference) }}</el-descriptions-item>
        <el-descriptions-item label="深度基准">{{ detail.reference?.depth_datum||'—' }} / {{ detail.reference?.depth_datum_elevation_m??'—' }} m</el-descriptions-item>
        <el-descriptions-item label="测斜工具">{{ detail.reference?.survey_tool?.name||'原件未注明' }}</el-descriptions-item>
        <el-descriptions-item label="工具型号 / 编号">{{ detail.reference?.survey_tool?.code||'—' }} / {{ detail.reference?.survey_tool?.serial_number||'—' }}</el-descriptions-item>
        <el-descriptions-item label="误差模型">{{ errorModelName(detail.effective_uncertainty?.settings?.uncertainty_model?.type||detail.effective_uncertainty?.model_version) }}</el-descriptions-item>
        <el-descriptions-item label="参数来源">{{ detail.effective_uncertainty?.parameter_source==='FIELD_CONFIGURATION'?'井场配置（未记录版本专用参数）':'本版本已确认参数' }}</el-descriptions-item>
      </el-descriptions>
      <div class="mb16"><el-button v-hasPermi="['drilling:well:survey:import']" plain type="primary" @click="startImport(selected.trajectory_kind, 'FULL')">完整新版本</el-button><el-button v-hasPermi="['drilling:well:survey:import']" :disabled="!selected" @click="startImport(selected.trajectory_kind, 'INCREMENTAL', selected)">增量导入</el-button><el-button v-if="selected?.trajectory_kind === 'ACTUAL'" v-hasPermi="['drilling:well:survey:import']" type="warning" plain @click="startImport('ACTUAL', 'RESURVEY', selected)">复测修订</el-button><el-button :disabled="sameKind.length < 2" @click="diffDialog.show(selected)">版本对比</el-button></div>
      <div class="mb16"><el-button v-if="detail" v-hasPermi="['drilling:well:survey:import']" @click="parametersDialog.show(detail)">修订测量参数</el-button><el-button :loading="exporting" @click="exportFile('TABLE')">轨道归算表</el-button><el-button v-if="patchSupported" :loading="exporting" @click="exportFile('PATCHED')">按源格式导出</el-button><el-button v-if="detail?.source_file_id" :loading="exporting" @click="exportFile('ORIGINAL')">来源原件</el-button></div>
      <el-collapse v-if="detail?.effective_uncertainty" class="mb16"><el-collapse-item title="本版本测量误差参数" name="parameters"><el-table :data="errorParameterRows({settings:detail.effective_uncertainty.settings,modelVersion:detail.effective_uncertainty.model_version}).filter(r=>r.key!=='k')" border max-height="300"><el-table-column prop="label" label="工程参数" min-width="260"/><el-table-column prop="value" label="值" min-width="180"/><el-table-column prop="unit" label="单位" width="100"/></el-table></el-collapse-item></el-collapse>
      <el-tabs v-model="view"><el-tab-pane label="测点数据" name="table"><el-table :data="pageRows" border max-height="450"><el-table-column v-for="c in columns" :key="c[0]" :label="c[1]" min-width="125"><template #default="s">{{ s.row[c[0]] == null ? '—' : Number(s.row[c[0]]).toFixed(3) }}</template></el-table-column></el-table><pagination v-model:page="page" v-model:limit="limit" :total="detail?.stations?.length || 0" /></el-tab-pane><el-tab-pane label="三维轨迹" name="3d" lazy><div v-if="view==='3d'" style="height:430px"><TrajectoryPlot :trajectories="[{id:'version',label:selected?.wellbore_code,points:detail?.plot_stations||detail?.stations||[],color:'#1976b9'}]" view="3d" /></div></el-tab-pane></el-tabs>
    </div>
    <template #footer><el-button @click="open=false">关 闭</el-button></template>
  </el-dialog>
  <SurveyImportDialog ref="importDialog" :field-id="fieldId" :bore="bore" :kind="kind" :versions="sameKind" :default-reference="{}" @saved="saved" />
  <SurveyVersionDiff ref="diffDialog" :field-id="fieldId" :versions="sameKind" />
  <SurveyParametersDialog ref="parametersDialog" :field-id="fieldId" :current-version-id="sameKind.find(v=>Number(v.is_active))?.survey_version_id" @saved="saved" />
</template>
<script setup>
import { computed, nextTick, ref } from 'vue'
import { fileDisplayName } from '@/utils/fileNames'
import { ElMessage } from 'element-plus'
import { trajectoryVersion, exportReduction, exportTrajectorySource, downloadBlob } from '@/api/drillingRegistry'
import TrajectoryPlot from '@/components/TrajectoryPlot'
import SurveyImportDialog from './SurveyImportDialog.vue'
import SurveyVersionDiff from './SurveyVersionDiff.vue'
import SurveyParametersDialog from './SurveyParametersDialog.vue'
import {errorModelName,errorParameterRows} from '@/utils/engineeringErrorModels'
const northLabel=value=>({GRID_NORTH:'网格北',TRUE_NORTH:'真北',MAGNETIC_NORTH:'磁北'}[value]||'—')
const props = defineProps({ fieldId: Number, node: Object, engineeringPath: String, versions: { type: Array, default: () => [] } })
const emit = defineEmits(['saved'])
const open=ref(false), loading=ref(false), selected=ref(null), detail=ref(null), page=ref(1), limit=ref(20), kind=ref('ACTUAL'), importDialog=ref(), diffDialog=ref()
const view=ref('table'),exporting=ref(false)
const parametersDialog=ref()
const patchSupported=computed(()=>detail.value?.source_file_id&&/\.(xls|xlsx|csv|tsv|txt|xml|witsml|dat|asc|mwd|docx)$/i.test(selected.value?.original_name||''))
const bore=computed(()=>({ ...selected.value, wellbore_id:selected.value?.wellbore_id || props.node?.wellbore_id, well_name:selected.value?.well_name || props.node?.well_name, well_code:selected.value?.well_code || props.node?.properties?.well_code || '', wellbore_name:selected.value?.wellbore_name || props.node?.node_name, wellbore_code:selected.value?.wellbore_code || props.node?.node_code || '', engineering_path:selected.value?.well_name ? null : props.engineeringPath }))
const sameKind=computed(()=>props.versions.filter(v=>Number(v.wellbore_id)===Number(bore.value.wellbore_id)&&v.trajectory_kind===kind.value).map(v=>({...v,is_active:v.is_current})))
const pageRows=computed(()=>(detail.value?.stations||[]).slice((page.value-1)*limit.value,page.value*limit.value))
const columns=[['md','MD（m）'],['inclination','井斜（°）'],['azimuth','方位（°）'],['north','N（m）'],['east','E（m）'],['tvd','TVD（m）'],['dogleg','狗腿（°/30m）']]
let sequence=0
async function show(row){const request=++sequence;selected.value=row;kind.value=row.trajectory_kind;page.value=1;view.value='table';detail.value=null;open.value=true;loading.value=true;try{const value=await trajectoryVersion(props.fieldId,row.survey_version_id);if(request===sequence)detail.value=value}catch(e){ElMessage.error(e.response?.data?.msg||e.message)}finally{if(request===sequence)loading.value=false}}
async function exportFile(mode){if(!selected.value)return;exporting.value=true;try{const row=selected.value;const blob=mode==='TABLE'?await exportReduction(props.fieldId,row.survey_version_id):await exportTrajectorySource(props.fieldId,row.survey_version_id,mode);downloadBlob(blob,mode==='TABLE'?`${row.wellbore_code}-v${row.version_no}.xlsx`:row.original_name||'trajectory')}catch(e){ElMessage.error(e.response?.data?.msg||e.message)}finally{exporting.value=false}}
async function startImport(value, mode='FULL', row=null){if(row)selected.value=row;else if(!open.value)selected.value=props.versions.find(v=>Number(v.wellbore_id)===Number(props.node?.wellbore_id)&&v.trajectory_kind===value)||null;kind.value=value;await nextTick();if(!bore.value.wellbore_id)return ElMessage.warning('请在工程树中选择具体井眼');importDialog.value.show(mode,row)}
function saved(){open.value=false;emit('saved')}
defineExpose({show,startImport})
</script>
