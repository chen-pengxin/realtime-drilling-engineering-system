<template>
  <el-dialog v-model="open" :title="form.importMode==='RESURVEY'?'实钻测斜复测修订':'导入轨迹版本'" width="1060px" append-to-body class="drilling-dialog" :close-on-click-modal="false" :before-close="beforeClose">
    <el-form label-width="110px" :disabled="busy" @submit.prevent>
      <el-form-item label="关联层级"><strong>{{ engineeringPath }}</strong></el-form-item>
      <el-row :gutter="20">
        <el-col :sm="12" :xs="24"><el-form-item label="井 / 井眼">{{ bore?.well_name || bore?.well_code }} / {{ wellboreName(bore) }}</el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="轨迹类型">{{ kind==='ACTUAL'?'实钻测斜':'设计方案' }}</el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="导入方式"><el-select v-model="form.importMode"><el-option label="完整新版本" value="FULL" /><el-option label="新增井段（增量）" value="INCREMENTAL" :disabled="!versions.length" /><el-option v-if="kind==='ACTUAL'" label="复测修订（历史测点）" value="RESURVEY" :disabled="!versions.length" /></el-select></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item :required="form.importMode!=='FULL'" label="父版本"><el-select v-model="form.parentVersionId" :clearable="form.importMode==='FULL'" filterable><el-option v-for="v in versions" :key="v.survey_version_id" :value="v.survey_version_id" :label="`v${v.version_no} · ${v.label||''}${Number(v.is_active)?'（当前）':''}`" /></el-select></el-form-item></el-col>
      </el-row>
      <el-form-item label="测斜文件" required>
        <el-button icon="Upload" @click="fileInput.click()">选择文件</el-button><el-button icon="FolderOpened" @click="sourceLibrary.show()">从资料库选择</el-button><span class="source-name">{{ sourceName || '支持 Excel、CSV、TXT、WITSML 和含测斜表的工程文档' }}</span>
        <input ref="fileInput" type="file" hidden accept=".xls,.xlsx,.csv,.tsv,.txt,.xml,.witsml,.dat,.asc,.mwd,.doc,.docx,.pdf" @change="selectFile" />
      </el-form-item>
      <el-form-item v-if="tables.length" label="源表 / 工作表" required><el-select v-model="extractionId" filterable><el-option v-for="t in tables" :key="t.extraction_id" :value="t.extraction_id" :label="`${t.locator} · ${t.payload.rows?.length||0} 个原始测点`" /></el-select></el-form-item>
      <el-alert v-if="form.importMode==='RESURVEY'" class="mb16" title="仅替换声明井深范围内的测点；范围外测量保留，并用最小曲率法重新归算后续坐标。旧版本保持不变。" type="info" :closable="false" />
      <el-row v-if="form.importMode==='RESURVEY'" :gutter="20">
        <el-col :sm="12" :xs="24"><el-form-item label="替换起点 MD" required><el-input-number v-model="form.replaceFromMd" :min="0" :precision="6" :controls="false" /><span class="unit">m</span></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="替换终点 MD" required><el-input-number v-model="form.replaceToMd" :min="0" :precision="6" :controls="false" /><span class="unit">m</span></el-form-item></el-col>
      </el-row>
      <el-collapse v-model="referencePanels"><el-collapse-item title="清洗规则" name="cleaning"><el-row :gutter="20"><el-col :sm="12" :xs="24"><el-form-item label="重复 MD 容差（m）"><el-input-number v-model="policy.duplicate_tolerance" :min="0.000001" :max="1" :precision="6" :step="0.000001" /></el-form-item></el-col><el-col :sm="12" :xs="24"><el-form-item label="狗腿异常阈值"><el-input-number v-model="policy.max_dls_deg_per_30m" :min="0.1" :max="180" /><span>°/30m</span></el-form-item></el-col><el-col :span="24"><el-form-item label="异常点处置"><el-checkbox v-model="policy.remove_anomalies">从本次新版本剔除狗腿异常点（原始资料保留）</el-checkbox></el-form-item></el-col></el-row><el-alert title="相同测点去重；同井深的冲突测量必须复核。剔除只作用于本次导入数据，已存父版本的测量不会被清洗规则改写。" type="info" :closable="false"/></el-collapse-item><el-collapse-item title="来源方位、单位与坐标基准" name="reference">
        <el-row :gutter="20">
          <el-col :sm="12" :xs="24"><el-form-item label="来源指北方向"><el-select v-model="reference.north_reference"><el-option label="网格北" value="GRID_NORTH" /><el-option label="真北" value="TRUE_NORTH" /><el-option label="磁北" value="MAGNETIC_NORTH" /></el-select></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="来源长度单位"><el-select v-model="reference.length_unit"><el-option label="米（m）" value="m" /><el-option label="英尺（ft）" value="ft" /></el-select></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="来源角度单位"><el-select v-model="reference.angle_unit"><el-option label="度（°）" value="deg" /><el-option label="弧度（rad）" value="rad" /></el-select></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="磁偏角（°）"><el-input-number v-model="reference.magnetic_declination" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="收敛角（°）"><el-input-number v-model="reference.meridian_convergence" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="坐标系 CRS"><el-input v-model="reference.crs" placeholder="未明确时留空，不能用于跨井安全评价" :disabled="form.importMode!=='FULL'" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="深度基准"><el-input v-model="reference.depth_datum" placeholder="例如 KB / MSL" :disabled="form.importMode!=='FULL'" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="基准高程（m）"><el-input-number v-model="reference.depth_datum_elevation_m" :precision="4" :controls="false" :disabled="form.importMode!=='FULL'" /></el-form-item></el-col>
          <template v-if="form.importMode==='FULL'"><el-col v-for="[key,label] in originFields" :key="key" :sm="8" :xs="24"><el-form-item :label="label"><el-input-number v-model="reference[key]" :precision="6" :controls="false" /></el-form-item></el-col></template>
        </el-row>
        <div class="form-tip">完整导入的首测点坐标使用来源长度单位；增量和复测始终沿用父版本的坐标基准及首测点坐标。</div>
      </el-collapse-item><el-collapse-item title="测斜工具与本版本误差参数" name="instrument">
        <el-row :gutter="20">
          <el-col :sm="12" :xs="24"><el-form-item label="测斜工具" required><el-input v-model="reference.survey_tool.name" placeholder="填写实际测斜工具或设计采用的工具" maxlength="120" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="工具型号 / 代码"><el-input v-model="reference.survey_tool.code" maxlength="120" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="仪器编号"><el-input v-model="reference.survey_tool.serial_number" maxlength="120" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="标定日期"><el-input v-model="reference.survey_tool.calibration_date" placeholder="按原件记录" maxlength="120" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="误差模型" required><el-input :model-value="errorModelName(configForm.algorithmDefaults.errorModel.modelVersion)" readonly /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="参数来源">{{ parameterSourceLabel }}</el-form-item></el-col>
        </el-row>
        <ErrorModelSettings v-model="reference.uncertainty.settings" />
        <el-table v-if="reference.survey_metadata_evidence?.length" :data="reference.survey_metadata_evidence" border max-height="180"><el-table-column prop="text" label="原件参数记录" min-width="260"/><el-table-column prop="line" label="解析文本行" width="110" /></el-table>
      </el-collapse-item></el-collapse>
      <el-row :gutter="20" class="version-fields">
        <el-col :sm="12" :xs="24"><el-form-item label="版本名称"><el-input v-model="form.label" maxlength="255" placeholder="可选，留空时自动编号" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="保存后启用"><el-switch v-model="form.activate" /><span class="unit">{{ form.activate?'设为当前版本':'仅保存历史分支' }}</span></el-form-item></el-col>
      </el-row>
      <el-form-item label="修订说明" :required="form.importMode==='RESURVEY'"><el-input v-model="form.note" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="填写复测原因、测量时间或仪器信息" /></el-form-item>
    </el-form>
    <el-alert v-if="error" class="mb16" :title="error" type="error" :closable="false" />
    <template v-if="preview">
      <el-descriptions :column="3" border class="mb16">
        <el-descriptions-item label="有效测点">{{ preview.count }}</el-descriptions-item>
        <el-descriptions-item label="新增 / 移除 / 修订">{{ preview.change_summary.added }} / {{ preview.change_summary.removed }} / {{ preview.change_summary.resurvey }}</el-descriptions-item>
        <el-descriptions-item label="重算井深范围">{{ preview.incremental_from_md==null?'无变化':`${preview.incremental_from_md}～${preview.downstream_recalculation.to_md} m` }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="preview.has_error" title="存在阻断性质量问题，不能保存。请修正源文件后重新导入。" type="error" :closable="false" />
      <el-table v-if="preview.issues.length" :data="preview.issues.slice(0,100)" border max-height="180"><el-table-column prop="type" label="质量检查" /><el-table-column prop="severity" label="等级" width="85" /><el-table-column prop="action" label="处理" width="135" /><el-table-column label="说明"><template #default="s">{{ s.row.message || `MD ${s.row.row?.md ?? '—'}` }}</template></el-table-column></el-table>
      <el-table :data="preview.changes" border max-height="230" empty-text="测点未发生变化，将复用已有版本">
        <el-table-column prop="md" label="变化 MD（m）" width="130" /><el-table-column label="变化" width="100"><template #default="s">{{ changeLabels[s.row.change] }}</template></el-table-column>
        <el-table-column label="原井斜 / 方位（°）"><template #default="s">{{ angles(s.row.before) }}</template></el-table-column><el-table-column label="新井斜 / 方位（°）"><template #default="s">{{ angles(s.row.after) }}</template></el-table-column>
      </el-table>
      <div v-if="preview.changes_truncated" class="form-tip">预览显示前 500 个变化点，保存时使用全部有效测点。</div>
    </template>
    <el-checkbox v-model="metadataConfirmed" :disabled="busy">已核对本版本的坐标基准、测斜工具和全井段误差参数</el-checkbox>
    <template #footer><el-button :disabled="busy" @click="open=false">取 消</el-button><el-button :loading="busy" :disabled="!extractionId" @click="calculatePreview">预览差异</el-button><el-button type="primary" :loading="saving" :disabled="busy || !preview || preview.has_error || !metadataConfirmed" @click="save">保存新版本</el-button></template>
  </el-dialog>
  <SourceLibrary ref="sourceLibrary" :field-id="fieldId" @select="loadSource"/>
  <SourceReview ref="sourceReviewDialog" :field-id="fieldId" @confirmed="onSourceConfirmed"/>
</template>
<script setup>
import {
  computed,
  reactive,
  ref,
  watch
} from 'vue'
import {
  ElMessage
} from 'element-plus'
import {
  boreVersions,
  commitSurveyRevision,
  previewSurveyRevision,
  sourceReview,
  sourceExtractions,
  trajectoryVersion,
  uploadSurveySource,
  engineCatalog
} from '@/api/drillingRegistry'
import SourceLibrary from './SourceLibrary.vue'
import SourceReview from './SourceReview.vue'
import ErrorModelSettings from '../../layout/ErrorModelSettings.vue'
import { errorModelName } from '@/utils/engineeringErrorModels'
import { wellboreName } from '@/utils/wellboreHierarchy'
import { fileDisplayName } from '@/utils/fileNames'
import { useDrillingContext } from '../../composables/useDrillingContext'
const { configForm } = useDrillingContext()
const catalog=ref({models:[]}),metadataConfirmed=ref(false)
const confirmedSourceReference=ref({})
const props = defineProps({
  fieldId: Number,
  bore: Object,
  kind: String,
  versions: {
    type: Array,
    default: () => []
  },
  defaultReference: Object
})
const emit = defineEmits(['saved'])
const engineeringPath=computed(()=>props.bore?.engineering_path || [props.bore?.block_name,props.bore?.field_name,props.bore?.well_name||props.bore?.well_code,wellboreName(props.bore)].filter(Boolean).join(' / '))
const open = ref(false),
  busy = ref(false),
  saving = ref(false),
  error = ref(''),
  preview = ref(null),
  fileInput = ref(),
  sourceName = ref(''),
  tables = ref([]),
  extractionId = ref(),
  referencePanels = ref([]),
  parentData = ref()
const form = reactive({
  importMode: 'FULL',
  parentVersionId: null,
  replaceFromMd: 0,
  replaceToMd: 0,
  label: '',
  note: '',
  activate: true
})
const reference = reactive({survey_tool:{},uncertainty:{settings:{}}}),
  originFields = [
    ['origin_north', '首点 N'],
    ['origin_east', '首点 E'],
    ['origin_tvd', '首点 TVD']
  ]
const sourceLibrary=ref(),sourceReviewDialog=ref(),pendingSource=ref(null),policy=reactive({duplicate_tolerance:0.000001,max_dls_deg_per_30m:30,remove_anomalies:false})
const changeLabels = {
  ADDED: '新增',
  REMOVED: '移除',
  RESURVEY: '复测修订'
}
const angles = p => p ? `${Number(p.inclination).toFixed(3)} / ${Number(p.azimuth).toFixed(3)}` : '—'
const currentId = computed(() => props.versions.find(v => Number(v.is_active))?.survey_version_id ?? null)
const parameterSourceLabel=computed(()=>({DOCUMENT_AND_REVIEW:'原件记录及人工核对',FIELD_SNAPSHOT:'工程默认参数',ENGINEERING_DEFAULT:'工程默认参数',PARENT_VERSION:'父版本参数',MANUAL:'人工设置'}[reference.uncertainty?.parameter_source]||'版本参数'))
function selectModel(name){const model=catalog.value.models.find(m=>m.name===name);if(model)reference.uncertainty={model_version:name,settings:JSON.parse(JSON.stringify(model.settings)),parameter_source:'MANUAL'}}
let requestNo = 0,
  opening = false,
  previewKey = '',
  expectedCurrentId = null
const geometry = () => ({
  wellboreId: props.bore.wellbore_id,
  kind: props.kind,
  importMode: form.importMode,
  parentVersionId: form.parentVersionId,
  replaceFromMd: form.replaceFromMd,
  replaceToMd: form.replaceToMd,
  policy:{...policy},
  reference: {...Object.fromEntries(Object.entries(reference).filter(([, v]) => v !== null && v !== undefined && v !== '')),uncertainty:{...reference.uncertainty,confirmed:metadataConfirmed.value}}
})
const key = () => {const payload=geometry();delete payload.reference.uncertainty.confirmed;return JSON.stringify([extractionId.value, payload])}

function beforeClose(done) {
  if (!busy.value && !saving.value) done()
}

function invalidate() {
  preview.value = null;
  previewKey = '';
  error.value = ''
}

function resetReference() {
  metadataConfirmed.value=false
  const table = tables.value.find(t => t.extraction_id === extractionId.value);
  const source = table?.payload.reference || {};
  const base = parentData.value?.reference || {};
  for (const k of Object.keys(reference)) delete reference[k];
  Object.assign(reference, {
    north_reference: 'GRID_NORTH',
    length_unit: 'm',
    angle_unit: 'deg',
    magnetic_declination: 0,
    meridian_convergence: 0,
    origin_north: 0,
    origin_east: 0,
    origin_tvd: 0
  }, props.defaultReference || {}, confirmedSourceReference.value, source);
  if(source.origin_north==null&&source.wellhead_north!=null&&Number(table?.payload.rows?.[0]?.md)===0)reference.origin_north=Number(source.wellhead_north)
  if(source.origin_east==null&&source.wellhead_east!=null&&Number(table?.payload.rows?.[0]?.md)===0)reference.origin_east=Number(source.wellhead_east)
  const inherited=form.importMode!=='FULL'?base:null
  const defaults=configForm.algorithmDefaults.errorModel
  reference.survey_tool=JSON.parse(JSON.stringify(source.survey_tool||inherited?.survey_tool||{}))
  const profile=source.uncertainty||inherited?.uncertainty
  reference.uncertainty=profile?{...JSON.parse(JSON.stringify(profile)),parameter_source:source.uncertainty?'DOCUMENT_AND_REVIEW':'PARENT_VERSION'}:{model_version:defaults.modelVersion,settings:JSON.parse(JSON.stringify(defaults.settings)),parameter_source:'FIELD_SNAPSHOT'}
  if(source.uncertainty_model_hint===defaults.modelVersion&&!profile){const model=catalog.value.models.find(m=>m.name===source.uncertainty_model_hint);if(model){reference.uncertainty.model_version=model.name;reference.uncertainty.settings=JSON.parse(JSON.stringify(model.settings))}}
  if(reference.uncertainty.model_version!==defaults.modelVersion)reference.uncertainty={model_version:defaults.modelVersion,settings:JSON.parse(JSON.stringify(defaults.settings)),parameter_source:'ENGINEERING_DEFAULT'}
  if(source.uncertainty_parameters){const spec=reference.uncertainty.settings.uncertainty_model||{};const values=spec.parameters||spec;for(const [key,value] of Object.entries(source.uncertainty_parameters))if(key in values)values[key]=value;reference.uncertainty.parameter_source='DOCUMENT_AND_REVIEW'}
  // A named modern ISCWSA tool has an explicit registry code; do not infer it
  // merely from the generic words MWD or gyro.
  if(reference.uncertainty.settings.uncertainty_model?.type==='iscwsa'&&source.survey_tool?.code)reference.uncertainty.settings.error_model=source.survey_tool.code
  if (form.importMode !== 'FULL')
    for (const k of ['crs', 'depth_datum', 'depth_datum_elevation_m']) {
      if (base[k] != null) reference[k] = base[k];
      else delete reference[k]
    }
  const scale = reference.length_unit === 'ft' ? 0.3048 : 1;
  const md = (table?.payload.rows || []).map(r => Number(r.md) * scale).filter(Number.isFinite);
  if (md.length) {
    form.replaceFromMd = Number(md.reduce((a, b) => Math.min(a, b), Infinity).toFixed(6));
    form.replaceToMd = Number(md.reduce((a, b) => Math.max(a, b), -Infinity).toFixed(6))
  }
}
async function loadParent() {
  const parent = form.parentVersionId;
  if (!parent) {
    parentData.value = null;
    resetReference();
    return
  }
  const n = ++requestNo;
  busy.value = true;
  try {
    const data = await trajectoryVersion(props.fieldId, parent);
    if (n !== requestNo || parent !== form.parentVersionId) return;
    parentData.value = data;
    resetReference();
    if (!opening) form.activate = parent === currentId.value
  } catch (e) {
    error.value = e.response?.data?.msg || e.message
  } finally {
    if (n === requestNo) busy.value = false
  }
}
watch(() => [form.parentVersionId, form.importMode], () => {
  if (open.value && !opening) {
    invalidate();
    loadParent()
  }
})
watch(extractionId, () => {
  if (open.value) {
    invalidate();
    resetReference()
  }
})
watch(() => [form.replaceFromMd, form.replaceToMd, JSON.stringify(reference)], () => { metadataConfirmed.value=false;invalidate() })
watch(policy,invalidate,{deep:true})
async function loadSource(source){
  invalidate();tables.value=[];extractionId.value=null;sourceName.value=fileDisplayName(source);busy.value=true
  try{const review=await sourceReview(Number(source.source_field_id || source.field_id) || props.fieldId,source.source_id);confirmedSourceReference.value=review.confirmed?.reference||{};if(review.status!=='CONFIRMED'){pendingSource.value=source;sourceReviewDialog.value?.show(source);error.value='该资料需要先完成工程确认';return}const entries=await sourceExtractions(Number(source.source_field_id || source.field_id) || props.fieldId,source.source_id);tables.value=entries.map(t=>({...t,payload:typeof t.payload_json==='string'?JSON.parse(t.payload_json):t.payload_json}));if(!tables.value.length)throw Error('资料中未识别到测斜表，请在资料库中使用字段映射核对原件行列');extractionId.value=source.extractionId||tables.value[0].extraction_id;resetReference()}
  catch(e){error.value=e.response?.data?.msg||e.message}finally{busy.value=false}
}
async function onSourceConfirmed(){const source=pendingSource.value;pendingSource.value=null;if(source)await loadSource(source)}
async function selectFile(event) {
  const file = event.target.files?.[0];
  event.target.value = '';
  if (!file) return;
  invalidate();
  tables.value = [];
  extractionId.value = null;
  sourceName.value = file.name;
  busy.value = true;
  try {
    const source = await uploadSurveySource(props.fieldId, file, {wellboreId:props.bore.wellbore_id,documentRole:'TRAJECTORY_DATA',trajectoryKind:props.kind});
    await loadSource(source)
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '文件解析失败'
  } finally {
    busy.value = false
  }
}
async function calculatePreview() {
  if (form.importMode !== 'FULL' && !form.parentVersionId) {
    error.value = '请选择父版本';
    return
  }
  if (form.importMode === 'RESURVEY' && (form.replaceFromMd == null || form.replaceToMd == null)) {
    error.value = '请填写替换井深范围';
    return
  }
  busy.value = true;
  error.value = '';
  const signature = key();
  try {
    const data = await previewSurveyRevision(props.fieldId, extractionId.value, geometry());
    if (signature === key()) {
      preview.value = data;
      previewKey = signature
    }
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '预览失败'
  } finally {
    busy.value = false
  }
}
async function save() {
  if (!preview.value || preview.value.has_error || previewKey !== key()) return;
  if(!metadataConfirmed.value||!reference.survey_tool?.name?.trim()){error.value='请填写测斜工具并核对本版本误差参数';referencePanels.value=['reference','instrument'];return}
  if (form.importMode === 'RESURVEY' && !form.note.trim()) {
    error.value = '请填写复测修订原因';
    return
  }
  saving.value = true;
  busy.value = true;
  error.value = '';
  try {
    const data = await commitSurveyRevision(props.fieldId, extractionId.value, {
      ...geometry(),
      label: form.label,
      note: form.note,
      activate: form.activate,
      expectedCurrentVersionId: expectedCurrentId
    });
    ElMessage.success(data.duplicate ? '测点未变化，已复用已有版本' : `已保存测斜版本 v${data.version_no}`);
    open.value = false;
    emit('saved', data)
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '保存失败';
    if (e.response?.status === 409) {
      preview.value = null;
      try {
        const fresh = await boreVersions(props.fieldId, props.bore.wellbore_id, props.kind);
        expectedCurrentId = fresh.find(v => Number(v.is_active))?.survey_version_id ?? null;
        emit('saved')
      } catch (_) {
        error.value += '；请关闭弹窗并刷新版本列表后重试'
      }
    }
  } finally {
    saving.value = false;
    busy.value = false
  }
}
async function show(mode = 'FULL', parent, source) {
  opening = true;
  requestNo++;
  preview.value = null;
  error.value = '';
  tables.value = [];
  extractionId.value = null;
  sourceName.value = '';
  confirmedSourceReference.value={};
  Object.assign(policy,{duplicate_tolerance:0.000001,max_dls_deg_per_30m:30,remove_anomalies:false});
  parentData.value = null;
  expectedCurrentId = currentId.value;
  Object.assign(form, {
    importMode: mode,
    parentVersionId: parent?.survey_version_id ?? currentId.value,
    replaceFromMd: 0,
    replaceToMd: 0,
    label: '',
    note: '',
    activate: !parent || parent.survey_version_id === currentId.value
  });
  referencePanels.value = ['reference','instrument'];
  metadataConfirmed.value=false;
  open.value = true;
  busy.value = true;
  try {
    // The directory mounts this dialog without opening it. Fetch model
    // metadata only when importing, so browsing/creating wells needs no engine.
    if (!catalog.value.models.length) catalog.value = await engineCatalog();
    await loadParent();
  } catch (e) {
    error.value = e.response?.data?.msg || e.message
  } finally {
    busy.value = false;
    opening = false
  }
  if(source)await loadSource(source)
}
watch(open, value => {
  if (!value) requestNo++
})
defineExpose({
  show
})
</script>
<style scoped>
.source-name{margin-left:12px;color:var(--el-text-color-regular);overflow-wrap:anywhere}.unit{margin-left:8px;color:var(--el-text-color-secondary)}.version-fields{margin-top:18px}.form-tip{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7;padding:8px 0}.el-select{width:100%}.el-alert{margin-bottom:12px}.el-collapse{margin-bottom:12px}.el-table{margin-top:12px}
</style>
