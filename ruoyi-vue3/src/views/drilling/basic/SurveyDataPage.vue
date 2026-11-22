<template>
  <DrillingPageFrame title="井与测斜数据">
    <el-tabs v-model="managementTab"><el-tab-pane v-for="tab in managementTabs" :key="tab.name" :name="tab.name" :label="tab.label" /></el-tabs>
    <EmbeddedDrilling v-if="managementTab==='versions'"><VersionCenter /></EmbeddedDrilling>
    <div v-else class="survey-layout">
      <section class="well-panel">
        <div class="panel-head"><h3>井 / 井眼</h3><div><el-button v-hasPermi="['drilling:well:add']" link type="primary" @click="showLink">引用已有</el-button><el-button v-hasPermi="['drilling:well:add']" type="primary" plain icon="Plus" @click="wellOpen=true">新增</el-button></div></div>
        <el-input v-model="wellKeyword" clearable placeholder="搜索井号、井眼" prefix-icon="Search" class="mb16" />
        <el-table ref="wellTable" v-loading="loadingBores" :data="filteredBores" highlight-current-row max-height="620" row-key="wellbore_id" @current-change="selectBore">
          <el-table-column prop="well_code" label="井号" min-width="100" sortable />
          <el-table-column prop="wellbore_code" label="井眼" min-width="100" sortable />
          <el-table-column label="施工状态" width="95"><template #default="s">{{ statusLabels[s.row.engineering_status]||s.row.engineering_status }}</template></el-table-column>
        </el-table>
      </section>
      <section class="data-panel">
        <div class="panel-head"><h3>{{ selectedBore ? `${selectedBore.well_code} / ${selectedBore.wellbore_code}` : '请选择井眼' }}</h3><el-button icon="Refresh" :loading="loadingVersions" @click="refresh">刷新</el-button></div>
        <div v-if="selectedBore" class="drilling-toolbar"><span>施工状态：{{ statusLabels[selectedBore.engineering_status] || selectedBore.engineering_status }}</span><el-button v-hasPermi="['drilling:well:edit']" link type="primary" icon="Edit" @click="showStatus">修改状态</el-button></div>
        <el-tabs v-model="kind"><el-tab-pane label="实钻测斜版本" name="ACTUAL" /><el-tab-pane label="设计方案版本" name="DESIGN" /></el-tabs>
        <div class="drilling-toolbar">
          <el-button v-hasPermi="['drilling:well:survey:import']" type="primary" plain icon="Upload" :disabled="!selectedBore" @click="importDialog.show('FULL')">导入新版本</el-button><el-button v-hasPermi="['drilling:well:survey:import']" plain icon="FolderOpened" :disabled="!fieldId" @click="batchInput?.click()">批量导入资料</el-button><input ref="batchInput" hidden type="file" multiple accept=".xls,.xlsx,.csv,.tsv,.txt,.xml,.witsml,.dat,.asc,.mwd,.doc,.docx,.pdf" @change="batchUpload" />
          <el-button icon="Files" :disabled="!fieldId" @click="sourceLibrary.show()">来源资料库</el-button>
          <el-button v-hasPermi="['drilling:well:survey:import']" type="success" plain :disabled="!currentVersion" @click="importDialog.show('INCREMENTAL',currentVersion)">增量导入</el-button>
          <el-button v-if="kind==='ACTUAL'" v-hasPermi="['drilling:well:survey:import']" type="warning" plain :disabled="!currentVersion" @click="importDialog.show('RESURVEY',currentVersion)">复测修订</el-button>
          <el-button icon="Sort" :disabled="versions.length<2" @click="diffDialog.show(displayedVersion||versions[0])">版本对比</el-button>
        </div>
        <el-table v-loading="loadingVersions" :data="versionPageRows" border highlight-current-row row-key="survey_version_id" @current-change="viewVersion">
          <el-table-column prop="version_no" label="版本" width="75" sortable><template #default="s">v{{ s.row.version_no }}</template></el-table-column>
          <el-table-column prop="label" label="名称" min-width="130" show-overflow-tooltip />
          <el-table-column label="来源" width="110"><template #default="s">{{ revisionLabels[s.row.revision_type] || s.row.import_mode }}</template></el-table-column>
          <el-table-column label="父版本" width="85"><template #default="s">{{ s.row.parent_version_no ? `v${s.row.parent_version_no}` : '—' }}</template></el-table-column>
          <el-table-column prop="max_md" label="最大 MD（m）" width="125" sortable />
          <el-table-column label="当前状态" width="95"><template #default="s"><el-tag v-if="Number(s.row.is_active)" type="success">当前</el-tag><span v-else>历史</span></template></el-table-column>
          <el-table-column prop="create_by" label="导入人" width="100" show-overflow-tooltip />
          <el-table-column prop="imported_at" label="导入时间" width="165" sortable><template #default="s">{{ String(s.row.imported_at||'').replace('T',' ') }}</template></el-table-column>
          <el-table-column prop="revision_note" label="修订说明" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" fixed="right" width="195"><template #default="s"><el-button link type="primary" @click.stop="viewVersion(s.row)">查看</el-button><el-button link type="primary" :disabled="versions.length<2" @click.stop="diffDialog.show(s.row)">对比</el-button><el-button v-if="!Number(s.row.is_active)" v-hasPermi="['drilling:well:survey:import']" link type="warning" @click.stop="activateVersion(s.row)">设为当前</el-button></template></el-table-column>
        </el-table>
        <pagination v-show="versions.length>10" v-model:page="versionPage" v-model:limit="versionLimit" :total="versions.length" />
        <template v-if="displayedVersion">
          <el-descriptions :column="2" border class="version-summary" size="small">
            <el-descriptions-item label="正在查看">v{{ displayedVersion.version_no }} · {{ Number(displayedVersion.is_active)?'当前版本':'历史版本' }}</el-descriptions-item>
            <el-descriptions-item label="来源文件">{{ displayedRow?.original_name || '实时接入 / 历史数据' }}</el-descriptions-item>
            <el-descriptions-item label="归算方法">最小曲率法</el-descriptions-item>
            <el-descriptions-item label="受影响井段">{{ affectedRange }}</el-descriptions-item>
          </el-descriptions>
          <div class="drilling-toolbar"><el-button icon="Download" :loading="exporting" @click="exportTable">导出轨道归算表</el-button><el-button v-if="sourcePatchSupported" icon="Download" :loading="exporting" @click="exportSourceVersion">按源格式导出当前版本</el-button><el-button v-if="displayedVersion.source_file_id" icon="Document" @click="downloadSource">下载来源原件</el-button></div>
          <el-tabs v-model="detailTab">
            <el-tab-pane label="测斜数据" name="table"><el-table v-loading="loadingStations" :data="stationPageRows" border><el-table-column v-for="c in stationColumns" :key="c[0]" :prop="c[0]" :label="c[1]" min-width="115"><template #default="s">{{ Number(s.row[c[0]]).toFixed(3) }}</template></el-table-column></el-table><pagination v-show="stations.length>0" v-model:page="stationPage" v-model:limit="stationLimit" :total="stations.length" /></el-tab-pane>
            <el-tab-pane label="三维轨迹" name="3d" lazy><div v-if="detailTab==='3d'" class="trajectory-view"><TrajectoryPlot :trajectories="[{id:'survey',label:'当前版本',points:displayedVersion.plot_stations||stations,color:'#1976b9'}]" view="3d" /></div></el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else-if="!loadingVersions" :description="selectedBore?'暂无轨迹版本，请先导入完整数据':'选择井眼后查看版本'" />
      </section>
    </div>
    <SurveyImportDialog ref="importDialog" :field-id="fieldId" :bore="selectedBore" :kind="kind" :versions="versions" :default-reference="defaultReference" @saved="versionSaved" />
    <SurveyVersionDiff ref="diffDialog" :field-id="fieldId" :versions="versions" />
    <SourceLibrary ref="sourceLibrary" :field-id="fieldId" @select="selectSource" />
    <el-dialog v-model="statusOpen" title="修改井眼施工状态" width="480px" append-to-body><el-form label-width="90px"><el-form-item label="施工状态"><el-select v-model="engineeringStatus"><el-option v-for="item in boreStates.filter(item=>item.status==='0')" :key="item.dictValue" :label="item.dictLabel" :value="item.dictValue" /></el-select></el-form-item></el-form><template #footer><el-button @click="statusOpen=false">取 消</el-button><el-button type="primary" :loading="savingStatus" @click="saveStatus">确 定</el-button></template></el-dialog>
    <el-dialog v-model="linkOpen" title="引用已有井眼" width="760px" append-to-body><el-input v-model="linkKeyword" placeholder="搜索井号、井眼" clearable class="mb16"/><el-table :data="filteredAvailable" v-loading="loadingAvailable" border max-height="440"><el-table-column prop="well_code" label="井号"/><el-table-column prop="well_name" label="井名"/><el-table-column prop="wellbore_code" label="井眼"/><el-table-column label="操作" width="100"><template #default="s"><el-button link type="primary" :loading="linking===s.row.wellbore_id" @click="linkBore(s.row)">引用</el-button></template></el-table-column></el-table><template #footer><el-button @click="linkOpen=false">关 闭</el-button></template></el-dialog>
    <el-dialog v-model="wellOpen" title="新增井 / 井眼" width="560px" append-to-body class="drilling-dialog">
      <el-form label-width="95px"><el-form-item label="井号" required><el-input v-model="well.wellCode" maxlength="64" @input="well.parentWellboreId=null" /></el-form-item><el-form-item label="井名"><el-input v-model="well.wellName" maxlength="128" /></el-form-item><el-form-item label="井眼编码" required><el-input v-model="well.wellboreCode" maxlength="128" /></el-form-item><el-form-item label="井眼名称"><el-input v-model="well.wellboreName" maxlength="200" /></el-form-item><el-form-item label="父井眼"><WellboreSelect v-model="well.parentWellboreId" :rows="bores.filter(b=>b.well_code===well.wellCode.trim())" placeholder="主井眼留空；侧钻选择父井眼" /></el-form-item></el-form>
      <template #footer><el-button @click="wellOpen=false">取 消</el-button><el-button type="primary" :loading="creatingWell" @click="createWell">确 定</el-button></template>
    </el-dialog>
  </DrillingPageFrame>
</template>

<script setup>
import WellboreSelect from '@/views/drilling/layout/WellboreSelect.vue'
import {defineAsyncComponent} from 'vue'
import EmbeddedDrilling from '../layout/EmbeddedDrilling.vue'
import {useManagementTabs} from '../composables/useManagementTabs'
const VersionCenter=defineAsyncComponent(()=>import('../centers/versions/index.vue'))
const {tabs:managementTabs,active:managementTab}=useManagementTabs([{name:'surveys',label:'轨迹版本'},{name:'versions',label:'版本与变更'}])

import {
  computed,
  nextTick,
  reactive,
  ref,
  watch
} from 'vue'
import {
  ElMessage,
  ElMessageBox
} from 'element-plus'
import {
  activateTrajectoryVersion,
  boreVersions,
  downloadBlob,
  exportReduction,
  exportTrajectorySource,
  listWellbores,
  registry,
  trajectoryVersion,
  uploadSourceBatch
} from '@/api/drillingRegistry'
import {
  downloadFieldFile
} from '@/api/drillingField'
import TrajectoryPlot from '@/components/TrajectoryPlot'
import {useDisplayDictionary} from '@/views/operations/useDisplayDictionary'
import DrillingPageFrame from '../layout/DrillingPageFrame.vue'
import {
  useDrillingContext
} from '../composables/useDrillingContext'
import SurveyImportDialog from './survey/SurveyImportDialog.vue'
import SurveyVersionDiff from './survey/SurveyVersionDiff.vue'
import SourceLibrary from './survey/SourceLibrary.vue'

const {
  fieldId,
  configForm,
  loadFieldContext
} = useDrillingContext()
const bores = ref([]),
  boreId = ref(),
  versions = ref([]),
  displayedVersion = ref(),
  wellKeyword = ref(''),
  kind = ref('ACTUAL'),
  detailTab = ref('table')
const wellOpen = ref(false),
  wellTable = ref(),
  importDialog = ref(),
  batchInput = ref(),
  diffDialog = ref(),
  loadingBores = ref(false),
  loadingVersions = ref(false),
  loadingStations = ref(false),
  exporting = ref(false),
  creatingWell = ref(false)
const versionPage = ref(1),
  versionLimit = ref(10),
  stationPage = ref(1),
  stationLimit = ref(20)
const well = reactive({
  wellCode: '',
  wellName: '',
  wellboreCode: 'MAIN',
  wellboreName: '', parentWellboreId: null
})
const sourceLibrary=ref(),statusOpen=ref(false),engineeringStatus=ref('UNKNOWN'),savingStatus=ref(false),linkOpen=ref(false),available=ref([]),loadingAvailable=ref(false),linking=ref(null),linkKeyword=ref('')
const filteredAvailable=computed(()=>available.value.filter(b=>`${b.well_code} ${b.well_name} ${b.wellbore_code}`.toLowerCase().includes(linkKeyword.value.toLowerCase())))
const sourcePatchSupported=computed(()=>displayedVersion.value?.source_file_id&&/\.(xls|xlsx|csv|tsv|txt|xml|witsml|dat|asc|mwd|docx)$/i.test(displayedRow.value?.original_name||''))
function showStatus(){engineeringStatus.value=selectedBore.value.engineering_status;statusOpen.value=true}
async function saveStatus(){savingStatus.value=true;try{await registry(fieldId.value,`/wellbores/${boreId.value}`,'put',{engineeringStatus:engineeringStatus.value});statusOpen.value=false;await loadBores();ElMessage.success('施工状态已更新')}catch(e){ElMessage.error(message(e))}finally{savingStatus.value=false}}
async function showLink(){linkOpen.value=true;loadingAvailable.value=true;try{available.value=await registry(fieldId.value,'/available-wellbores')}catch(e){ElMessage.error(message(e))}finally{loadingAvailable.value=false}}
async function linkBore(row){linking.value=row.wellbore_id;try{await registry(fieldId.value,`/wellbores/${row.wellbore_id}/link`,'post');available.value=available.value.filter(b=>b.wellbore_id!==row.wellbore_id);await loadBores();boreId.value=row.wellbore_id;ElMessage.success('已引用原井眼及其版本，无需重复导入')}catch(e){ElMessage.error(message(e))}finally{linking.value=null}}
function selectSource(source){if(!selectedBore.value){ElMessage.warning('请先选择目标井眼，再从资料库选择来源');return}importDialog.value.show('FULL',null,source)}
const defaultStatusLabels = {
  UNPLANNED: '待规划',
  PLANNED: '已规划',
  DRILLING: '正在钻进',
  SUSPENDED: '暂停',
  COMPLETED: '已完成',
  ABANDONED: '已废弃',
  UNKNOWN: '待核实'
}
const boreStates = useDisplayDictionary('wellbore_state')
const statusLabels = computed(() => ({...defaultStatusLabels, ...Object.fromEntries(boreStates.value.map(item=>[item.dictValue,item.dictLabel]))}))
const revisionLabels = {
  FULL: '完整导入',
  INCREMENTAL: '增量导入',
  RESURVEY: '复测修订',
  REPLACE: '修订'
}
const stationColumns = [
  ['md', 'MD（m）'],
  ['inclination', '井斜（°）'],
  ['azimuth', '方位（°）'],
  ['north', 'N（m）'],
  ['east', 'E（m）'],
  ['tvd', 'TVD（m）']
]
const selectedBore = computed(() => bores.value.find(b => b.wellbore_id === boreId.value))
const filteredBores = computed(() => {
  const word = wellKeyword.value.trim().toLowerCase();
  return bores.value.filter(b => `${b.well_code} ${b.well_name} ${b.wellbore_code}`.toLowerCase().includes(word))
})
const currentVersion = computed(() => versions.value.find(v => Number(v.is_active)))
const displayedRow = computed(() => versions.value.find(v => v.survey_version_id === displayedVersion.value?.survey_version_id))
const versionPageRows = computed(() => versions.value.slice((versionPage.value - 1) * versionLimit.value, versionPage.value * versionLimit.value))
const stations = computed(() => displayedVersion.value?.stations || [])
const stationPageRows = computed(() => stations.value.slice((stationPage.value - 1) * stationLimit.value, stationPage.value * stationLimit.value))
const affectedRange = computed(() => {
  const summary = displayedVersion.value?.revision_summary;
  return summary?.from_md == null ? '—' : `${summary.from_md}～${summary.to_md} m`
})
const defaultReference = computed(() => ({
  north_reference: configForm.northReference || 'GRID_NORTH',
  length_unit: configForm.lengthUnit || 'm',
  angle_unit: configForm.angleUnit || 'deg',
  magnetic_declination: configForm.magneticDeclination || 0,
  meridian_convergence: configForm.meridianConvergence || 0,
  origin_north: configForm.originNorth || 0,
  origin_east: configForm.originEast || 0,
  origin_tvd: configForm.originTvd || 0
}))
let boreRequest = 0,
  versionRequest = 0,
  stationRequest = 0
const message = e => e?.response?.data?.msg || e?.message || '操作失败'
async function loadBores() {
  const p = fieldId.value,
    n = ++boreRequest;
  bores.value = [];
  if (!p) {
    boreId.value = null;
    loadingBores.value = false;
    return
  }
  loadingBores.value = true;
  try {
    const rows = await listWellbores(p);
    if (n !== boreRequest || p !== fieldId.value) return;
    bores.value = rows;
    const next = rows.find(b => b.wellbore_id === boreId.value) || rows[0];
    if (next?.wellbore_id === boreId.value) await loadVersions();
    else boreId.value = next?.wellbore_id;
    await nextTick();
    wellTable.value?.setCurrentRow(next)
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    if (n === boreRequest) loadingBores.value = false
  }
}

function selectBore(row) {
  if (row && row.wellbore_id !== boreId.value) boreId.value = row.wellbore_id
}
async function loadVersions(preferred) {
  const p = fieldId.value,
    b = boreId.value,
    k = kind.value,
    n = ++versionRequest;
  stationRequest++;
  versions.value = [];
  displayedVersion.value = null;
  loadingVersions.value = false;
  loadingStations.value = false;
  stationPage.value = 1;
  versionPage.value = 1;
  if (!p || !b) return;
  loadingVersions.value = true;
  try {
    const rows = await boreVersions(p, b, k);
    if (n !== versionRequest || p !== fieldId.value || b !== boreId.value || k !== kind.value) return;
    versions.value = rows;
    const next = rows.find(v => v.survey_version_id === preferred) || rows.find(v => Number(v.is_active)) || rows[0];
    if (next) await viewVersion(next)
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    if (n === versionRequest) loadingVersions.value = false
  }
}
async function viewVersion(row) {
  if (!row) return;
  const p = fieldId.value,
    b = boreId.value,
    k = kind.value,
    n = ++stationRequest;
  loadingStations.value = true;
  try {
    const data = await trajectoryVersion(p, row.survey_version_id);
    if (n !== stationRequest || p !== fieldId.value || b !== boreId.value || k !== kind.value) return;
    displayedVersion.value = data;
    stationPage.value = 1
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    if (n === stationRequest) loadingStations.value = false
  }
}
async function activateVersion(row) {
  try {
    await ElMessageBox.confirm(`将 ${selectedBore.value.well_code} / ${selectedBore.value.wellbore_code} 的${kind.value==='ACTUAL'?'实钻':'设计'}当前版本切换为 v${row.version_no}？历史任务仍保留原版本记录。`, '切换当前版本', {
      type: 'warning'
    });
    const data = await activateTrajectoryVersion(fieldId.value, row.survey_version_id, currentVersion.value?.survey_version_id ?? null);
    await versionSaved(data);
    ElMessage.success(`当前版本已切换为 v${row.version_no}`)
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(message(e))
  }
}
async function versionSaved(data) {
  await loadVersions(data?.survey_version_id);
  await loadFieldContext({
    basicOnly: true
  });
  window.dispatchEvent(new CustomEvent('drilling-survey-version-changed', {
    detail: {
      fieldId: fieldId.value,
      wellboreId: boreId.value,
      kind: kind.value,
      versionId: data?.survey_version_id
    }
  }))
}
async function refresh() {
  await loadBores()
}
async function createWell() {
  if (!fieldId.value || !well.wellCode.trim() || !well.wellboreCode.trim()) {
    ElMessage.warning('请选择井场并填写井号和井眼编码');
    return
  }
  creatingWell.value = true;
  try {
    const created = await registry(fieldId.value, '/wellbores', 'post', {
      ...well,
      wellName: well.wellName || well.wellCode,
      wellboreName: well.wellboreName || well.wellboreCode,
      reference: defaultReference.value
    });
    wellOpen.value = false;
    await loadBores();
    boreId.value = created.wellbore_id;
    Object.assign(well, {
      wellCode: '',
      wellName: '',
      wellboreCode: 'MAIN',
      wellboreName: '',parentWellboreId:null
    });
    ElMessage.success('井眼已创建')
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    creatingWell.value = false
  }
}
async function exportTable() {
  exporting.value = true;
  try {
    const data = await exportReduction(fieldId.value, displayedVersion.value.survey_version_id);
    if (data.type?.includes('json')) throw new Error('轨道归算导出失败，请检查算法服务');
    downloadBlob(data, `${selectedBore.value.well_code}-${selectedBore.value.wellbore_code}-v${displayedVersion.value.version_no}-轨道归算.xlsx`)
  } catch (e) {
    ElMessage.error(message(e))
  } finally {
    exporting.value = false
  }
}
async function exportSourceVersion() {
  exporting.value=true
  try { const data=await exportTrajectorySource(fieldId.value,displayedVersion.value.survey_version_id);downloadBlob(data,displayedRow.value?.original_name||'轨迹版本导出') }
  catch(e){ElMessage.error(message(e))} finally{exporting.value=false}
}
async function downloadSource() {
  try {
    const data = await downloadFieldFile(fieldId.value, displayedVersion.value.source_file_id);
    downloadBlob(data, displayedRow.value?.original_name || '测斜来源文件')
  } catch (e) {
    ElMessage.error(message(e))
  }
}
async function batchUpload(event) {
  const files=[...(event.target.files||[])]; event.target.value=''; if(!files.length||!fieldId.value)return
  try { const result=await uploadSourceBatch(fieldId.value,files); ElMessage.success(`批量资料处理完成：成功 ${result.succeeded||0} 个，失败 ${result.failed||0} 个`);await sourceLibrary.value.showReviewQueue(result.rows||[]) }
  catch(e){ElMessage.error(e.response?.data?.msg||e.message||'批量导入失败')}
}
watch(fieldId, () => {
  boreId.value = null;
  versions.value = [];
  displayedVersion.value = null;
  loadBores()
}, {
  immediate: true
})
watch([boreId, kind], () => loadVersions())
</script>

<style scoped>
.survey-layout{display:grid;grid-template-columns:320px minmax(0,1fr);gap:20px}.well-panel,.data-panel{min-width:0}.well-panel{padding-right:20px;border-right:1px solid var(--el-border-color-light)}.panel-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px;gap:10px}.panel-head h3{font-size:14px;margin:0}.version-summary{margin:18px 0 12px}.version-notice{margin-bottom:14px}.trajectory-view{height:480px}.trajectory-view :deep(.scene){height:100%}.drilling-toolbar{margin:8px 0 16px}@media(max-width:1100px){.survey-layout{grid-template-columns:250px minmax(0,1fr)}}@media(max-width:800px){.survey-layout{grid-template-columns:1fr}.well-panel{padding:0 0 16px;border-right:0;border-bottom:1px solid var(--el-border-color-light)}.well-panel :deep(.el-table){max-height:220px}.trajectory-view{height:320px}}
</style>
