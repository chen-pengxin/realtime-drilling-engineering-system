<template>
  <ResizableDialog v-model="open" :title="versionTarget ? '导入文件版次' : '导入来源文件'" width="min(1120px, calc(100vw - 32px))" append-to-body :close-on-click-modal="false" :before-close="beforeClose">
    <el-descriptions v-if="versionTarget" :column="1" border class="version-summary"><el-descriptions-item label="资料名称">{{ fileDisplayName(versionTarget) }}</el-descriptions-item><el-descriptions-item label="最新文件版次">第 {{ versionTarget.latest_edition_no }} 版</el-descriptions-item><el-descriptions-item label="更新范围" :span="2">保存新原件并保留历史文件；轨迹资料解析确认后生成新版本并更新当前指针。</el-descriptions-item></el-descriptions>
    <el-form label-width="100px" :disabled="uploading" @submit.prevent>
      <el-form-item label="关联对象" required>
        <el-tree-select v-model="defaultNodeId" :data="targetTree" node-key="node_id" :props="treeProps" check-strictly filterable :render-after-expand="false" :disabled="!!versionTarget" placeholder="选择所属井场、井、井眼或资料目录" class="target-select" />
        <el-button v-if="!versionTarget" :disabled="!defaultNodeId || !rows.length" @click="applyTarget">应用到全部文件</el-button>
      </el-form-item>
      <el-form-item label="工程层级"><span class="engineering-path">{{ nodePath(defaultNodeId) || '请选择关联对象' }}</span></el-form-item>
    </el-form>
    <div class="file-toolbar">
  <input ref="fileInput" type="file" :multiple="!versionTarget" hidden @change="selectFiles" />
  <input ref="folderInput" type="file" webkitdirectory directory multiple hidden @change="selectFolder" />
  <el-button icon="Plus" :disabled="uploading || !defaultNodeId || !!versionTarget && !!completed.length" @click="fileInput.click()">选择文件</el-button>
  <el-button v-if="!versionTarget" icon="FolderOpened" :disabled="uploading || !defaultNodeId" @click="folderInput.click()">选择文件夹</el-button>
      <span>{{ versionTarget ? '选择一份替换文件，保存后请核对解析结果。' : '本批文件逐项显示归属，可分别修改；原件入库后仍需核对解析结果。' }}</span>
    </div>
    <el-table :data="rows" border max-height="430" row-key="id">
      <el-table-column label="原始文件名" min-width="190"><template #default="s"><div>{{ s.row.file.name }}</div><small>{{ sizeText(s.row.file.size) }}</small></template></el-table-column>
      <el-table-column label="文件夹路径" min-width="220" show-overflow-tooltip><template #default="s">{{ s.row.relativePath || '—' }}</template></el-table-column>
      <el-table-column label="解析出的工程层级" min-width="300" show-overflow-tooltip><template #default="s"><span v-if="s.row.source?.parsed_engineering">{{ parsedPath(s.row.source.parsed_engineering, s.row.source.parsed_metadata) }}</span><span v-else>上传后自动解析</span></template></el-table-column>
      <el-table-column label="系统名称" min-width="190"><template #default="s"><span>{{ s.row.displayName }}</span></template></el-table-column>
      <el-table-column label="关联层级" min-width="300"><template #default="s">
        <el-tree-select v-model="s.row.nodeId" :data="targetTree" node-key="node_id" :props="treeProps" check-strictly filterable :render-after-expand="false" :disabled="uploading || s.row.status==='SUCCESS' || !!versionTarget" @change="changeTarget(s.row)" class="full" />
        <div class="row-path">{{ nodePath(s.row.nodeId) }}</div>
      </template></el-table-column>
      <el-table-column label="文件用途" width="150"><template #default="s"><el-select v-model="s.row.role" :disabled="uploading || s.row.status==='SUCCESS' || !!versionTarget" @change="refreshDisplayName(s.row)"><el-option v-for="(label,key) in documentRoles" :key="key" :label="label" :value="key" /></el-select></template></el-table-column>
      <el-table-column label="轨迹类别" width="140"><template #default="s">
        <el-select v-if="['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(s.row.role)" v-model="s.row.kind" :disabled="uploading || s.row.status==='SUCCESS' || !!versionTarget?.trajectory_kind || ['PLAN','SURVEY'].includes(nodeFor(s.row.nodeId)?.node_type)" clearable placeholder="选择用途"><el-option label="设计方案" value="DESIGN" /><el-option label="实钻测斜" value="ACTUAL" /></el-select><span v-else>—</span>
      </template></el-table-column>
      <el-table-column label="导入状态" min-width="150"><template #default="s"><el-tag v-if="s.row.status==='SUCCESS'" type="success">{{ versionTarget ? (s.row.source?.file_unchanged ? '文件内容未变化' : `已保存第 ${s.row.source?.edition_no} 版`) : '已保存' }}</el-tag><el-tag v-else-if="s.row.status==='CONFIRMED'" type="success">已解析并确认</el-tag><el-tag v-else-if="s.row.status==='PARSED'" type="warning">已解析，待确认</el-tag><span v-else-if="s.row.status==='UPLOADING'">正在解析入库</span><span v-else-if="s.row.error" class="error-text">{{ s.row.error }}</span><span v-else>待解析</span></template></el-table-column>
      <el-table-column label="操作" width="70"><template #default="s"><el-button v-if="s.row.status!=='SUCCESS'" link type="danger" :disabled="uploading" @click="removeRow(s.row)">移除</el-button></template></el-table-column>
    </el-table>
    <section v-if="reviewRows.length" class="inline-review">
      <div class="inline-review-title">批量解析结果确认</div>
      <el-alert type="info" :closable="false" class="review-scope-tip">每个文件可以独立修改关联层级；当前导入层级及其上级锁定，只能修改解析出的下级层级。全部文件核对完成后统一确认。</el-alert>
      <div v-for="row in reviewRows" :key="row.id" class="file-review-row">
        <div class="file-review-name">{{ row.file.name }}</div>
        <el-form label-width="58px" :disabled="reviewSaving">
          <el-row :gutter="14">
            <el-col :sm="12" :xs="24"><el-form-item label="区块"><el-input v-model="row.review.block" :disabled="row.reviewLocks.block" /></el-form-item></el-col>
            <el-col :sm="12" :xs="24"><el-form-item label="井场"><el-input v-model="row.review.field" :disabled="row.reviewLocks.field" /></el-form-item></el-col>
            <el-col :sm="12" :xs="24"><el-form-item label="井"><el-input v-model="row.review.well" :disabled="row.reviewLocks.well" /></el-form-item></el-col>
            <el-col :sm="12" :xs="24"><el-form-item label="井眼"><el-input v-model="row.review.wellbore" :disabled="row.reviewLocks.wellbore" /></el-form-item></el-col>
          </el-row>
        </el-form>
      </div>
      <div class="inline-review-actions">
        <el-checkbox v-model="associationConfirmed">是否确认全部文件的关联层级正确</el-checkbox>
        <el-button type="primary" :loading="reviewSaving" :disabled="!associationConfirmed" @click="confirmAllInlineReviews">确认全部解析结果</el-button>
      </div>
    </section>
    <div v-if="error" class="error-text import-error">{{ error }}</div>
    <el-progress v-if="uploading" :percentage="progress" class="import-progress" />
    <template #footer><el-button :disabled="uploading || reviewSaving || reviewRows.length" @click="close">{{ completed.length ? '完成' : '取消' }}</el-button><el-button v-if="versionTarget" type="primary" :loading="uploading" :disabled="!pending.length" @click="submit">保存文件版次</el-button></template>
  </ResizableDialog>
</template>

<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadSurveySource, sourceReview, confirmSourceReview } from '@/api/drillingRegistry'
import { libraryDocument } from '@/api/engineeringLibrary'
import { fileDisplayName } from '@/utils/fileNames'
import { catalogFileTargetAllowed, catalogUploadError } from '@/utils/engineeringCatalog'

const props = defineProps({ nodes: { type: Array, default: () => [] }, documentRoles: Object, fieldId: Number })
const emit = defineEmits(['uploaded'])
const open = ref(false), uploading = ref(false), defaultNodeId = ref(null), rows = ref([]), fileInput = ref(), folderInput = ref(), error = ref(''), activeFieldId = ref(null), activeBlockId = ref(null), activeScopeType = ref(''), activeNodeId = ref(null), reviewSaving = ref(false), associationConfirmed = ref(false)
const versionTarget = ref(null)
const treeProps = { label: 'label', children: 'children', disabled: 'disabled' }
const nodeMap = computed(() => new Map(props.nodes.map(n => [Number(n.node_id), n])))
const targetTree = computed(() => {
  const all = new Map(props.nodes.map(n => [Number(n.node_id), n]))
  const inScope = n => {
    if (Number(n.node_id) === Number(activeNodeId.value)) return true
    if (n.node_type === 'BLOCK') return Number(n.block_id) === Number(activeBlockId.value)
    if (activeScopeType.value === 'BLOCK') return Number(n.block_id) === Number(activeBlockId.value)
    let current=n, seen=new Set()
    while(current && !seen.has(Number(current.node_id))){ if(Number(current.parent_id)===Number(activeNodeId.value)) return true; seen.add(Number(current.node_id)); current=all.get(Number(current.parent_id)) }
    return false
  }
  const available = props.nodes.filter(inScope)
  const nodes = available.map(n => ({ ...n, children: [], disabled: !catalogFileTargetAllowed(n) }))
  const map = new Map(nodes.map(n => [Number(n.node_id), n])), roots = []
  nodes.forEach(n => { const parent = map.get(Number(n.parent_id)); if (parent) parent.children.push(n); else roots.push(n) })
  // Keep an empty block selectable. It is still the user's explicit import
  // scope; if no storage field exists, submit() reports that prerequisite.
  return roots.filter(n => n.node_type !== 'BLOCK' || n.children.length || activeScopeType.value === 'BLOCK')
})
const nodeFor = id => nodeMap.value.get(Number(id))
const nodePath = id => nodeFor(id)?.engineering_path || ''
const parsedPath = (engineering, metadata={}) => { const e=engineering||{}; return [e.block||e.block_name, e.field||e.field_name||e.site, e.well||e.well_name, e.wellbore||e.wellbore_name].filter(Boolean).join(' / ') || metadata?.import_scope_path || '未识别，待确认' }
function generatedName(node, role, kind, edition=1) {
  const path=String(node?.engineering_path||'').split(' / ').filter(Boolean)
  const well=path.at(-2) || node?.well_name || '井'
  const type=kind==='DESIGN'?'设计方案':kind==='ACTUAL'?'实测测斜':role==='ENGINEERING_REPORT'?'工程报告':'工程资料'
  return `${well}_${type}_${edition}`
}
const completed = computed(() => rows.value.filter(r => ['SUCCESS','CONFIRMED'].includes(r.status)))
const pending = computed(() => rows.value.filter(r => r.status !== 'SUCCESS'))
const reviewRows = computed(() => rows.value.filter(r => r.status === 'PARSED' && r.review))
const progress = computed(() => Math.round(100 * rows.value.filter(r => ['SUCCESS','FAILED'].includes(r.status)).length / Math.max(rows.value.length,1)))
const sizeText = size => size >= 1048576 ? `${(size / 1048576).toFixed(1)} MB` : `${Math.ceil(size / 1024)} KB`
let serial = 0
function show(node) {
  versionTarget.value = null
  uploading.value = false
  reviewSaving.value = false
  activeFieldId.value = node?.field_id || props.fieldId
  activeBlockId.value = node?.block_id || props.nodes.find(item => Number(item.field_id) === Number(activeFieldId.value))?.block_id || null
  if (!activeFieldId.value && node?.node_type === 'BLOCK') {
    activeFieldId.value = props.nodes.find(item => item.node_type === 'FIELD' && Number(item.block_id) === Number(node.block_id))?.field_id || props.nodes.find(item => item.node_type === 'FIELD')?.field_id || null
  }
  activeScopeType.value = node?.node_type || ''
  activeNodeId.value = node?.node_id || null
  defaultNodeId.value = catalogFileTargetAllowed(node) ? Number(node.node_id) : null
  rows.value = []; error.value = ''; associationConfirmed.value = false; open.value = true
}
async function showVersion(row) {
  try {
    const node=nodeFor(row.node_id)
    if(!node)throw Error('请刷新工程目录后重试')
    const document=await libraryDocument(node.field_id,row.document_id)
    show(node);versionTarget.value=document
  } catch(e) { ElMessage.error(e.response?.data?.msg || e.message) }
}
function refreshDisplayName(row) { if (!versionTarget.value) row.displayName = generatedName(nodeFor(row.nodeId), row.role, row.kind, 1) }
function changeTarget(row) { const type = nodeFor(row.nodeId)?.node_type; row.kind = type === 'PLAN' ? 'DESIGN' : type === 'SURVEY' ? 'ACTUAL' : ''; row.error = ''; refreshDisplayName(row) }
function applyTarget() { pending.value.forEach(row => { row.nodeId = defaultNodeId.value; changeTarget(row) }) }
function inferRole(file) { const ext = file.name.split('.').pop().toLowerCase(); return ['csv','xls','xlsx','txt','tsv','dat','asc','mwd','xml','witsml'].includes(ext) ? 'TRAJECTORY_DATA' : ['pdf','doc','docx'].includes(ext) ? 'ENGINEERING_REPORT' : ['zip','rar','7z'].includes(ext) ? 'ARCHIVE' : 'REFERENCE' }
function addFiles(inputFiles) {
  const files = [...inputFiles].filter(file => file && file.size >= 0 && !file.name.startsWith('.'))
  if(versionTarget.value){if(files.length!==1)return ElMessage.warning('更新文件时请选择一个文件');rows.value=[]}
  if (files.length + rows.value.length > 200) return ElMessage.warning('每批最多 200 个文件')
  const added=[]
  for (const file of files) {
    const relativePath=file.webkitRelativePath||file.name
    if (rows.value.some(r => r.file.name === file.name && r.file.size === file.size && r.file.lastModified === file.lastModified && r.relativePath === relativePath)) continue
    const role=versionTarget.value?.document_role || inferRole(file); const kind=versionTarget.value?.trajectory_kind || ''; const row = { id: ++serial, file, relativePath, displayName: versionTarget.value?.display_name || generatedName(nodeFor(defaultNodeId.value),role,kind,versionTarget.value?.latest_edition_no?Number(versionTarget.value.latest_edition_no)+1:1), nodeId: defaultNodeId.value, role, kind, status: 'PENDING', error: '' }; changeTarget(row);if(versionTarget.value?.trajectory_kind)row.kind=versionTarget.value.trajectory_kind; rows.value.push(row); added.push(rows.value[rows.value.length - 1])
  }
  if (!versionTarget.value && added.length) autoParseRows(added)
}
function selectFiles(event) { const files=[...(event.target.files||[])]; event.target.value=''; addFiles(files) }
function selectFolder(event) { const files=[...(event.target.files||[])]; event.target.value=''; addFiles(files) }
function removeRow(row) { rows.value = rows.value.filter(r => r.id !== row.id) }
async function autoParseRows(items) {
  error.value=''; uploading.value=true
  const queue=[...items]
  const parseOne=async row=>{
    if (row.status!=='PENDING') return
    const target=parseInvalid(row); if(target){row.error=target;row.status='FAILED';return}
    row.status='UPLOADING'; row.error=''
    try {
      const node=nodeFor(row.nodeId), storageFieldId=node?.field_id||activeFieldId.value
      if(!storageFieldId) throw Error('当前工程范围没有可用的文件存储井场，请先建立井场')
      row.source=await uploadSurveySource(storageFieldId,row.file,{nodeId:row.nodeId,sourcePath:row.relativePath||row.file.name,documentRole:row.role,trajectoryKind:['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(row.role)?row.kind:''})
      const parsedEngineering=row.source?.parsed_engineering||row.source?.parsed_metadata?.engineering||{}
      const parsedKind=parsedEngineering.trajectory_kind||row.source?.parsed_metadata?.trajectory_kind||''
      if(['DESIGN','ACTUAL'].includes(parsedKind) && !['DESIGN','ACTUAL'].includes(row.kind)) row.kind=parsedKind
      const parsedRole=parsedEngineering.document_role||row.source?.parsed_metadata?.document_role||''
      if(parsedRole && Object.prototype.hasOwnProperty.call(props.documentRoles || {},parsedRole)) row.role=parsedRole
      refreshDisplayName(row)
      row.status='PARSED'
      await loadReview(row)
    } catch(e) { row.status='FAILED';row.error=e.response?.data?.msg||e.message||'解析失败' }
  }
  try {
    // Office/PDF parsing is CPU and I/O heavy. Keep a small bounded worker
    // pool so a folder containing independent wells does not wait for every
    // document serially, while avoiding an unbounded burst against the
    // parser and MySQL.
    const workerCount=Math.min(4,queue.length)
    await Promise.all(Array.from({length:workerCount},async()=>{while(queue.length) await parseOne(queue.shift())}))
  } finally { uploading.value=false }
}
function reviewLocksFor(scope) {
  const value=String(scope||'').toUpperCase()
  return {block:['BLOCK','FIELD','WELL','WELLBORE'].includes(value),field:['FIELD','WELL','WELLBORE'].includes(value),well:['WELL','WELLBORE'].includes(value),wellbore:value==='WELLBORE'}
}
async function loadReview(row) {
  try {
    const field=Number(row.source?.source_field_id||row.source?.field_id||activeFieldId.value)
    const review=await sourceReview(field,row.source.source_id)
    const p=review?.status==='CONFIRMED'?review.confirmed:review?.proposed||{},e=p.engineering||p
    const linked=review?.associations?.length===1?review.associations[0]:{}
    row.reviewData=review
    row.reviewLocks=reviewLocksFor(review?.metadata?.import_scope_type||row.source?.import_scope_type)
    row.review={block:p.block||e.block||e.block_name||linked.block_name||'',field:p.field||e.field||e.field_name||e.site||linked.field_name||'',well:p.well||e.well||linked.well_code||linked.well_name||'',wellbore:p.wellbore||e.wellbore||linked.wellbore_code||linked.wellbore_name||'',neighborText:(p.neighbor_candidates||e.neighbor_candidates||[]).join(',')}
  } catch(e) { row.error=e.response?.data?.msg||e.message||'解析结果读取失败';row.status='FAILED' }
}
async function confirmAllInlineReviews() {
  if(!associationConfirmed.value||!reviewRows.value.length)return
  reviewSaving.value=true;error.value=''
  try {
    for(const row of reviewRows.value){
      const f=row.review,neighbors=String(f.neighborText||'').split(/[,，\s]+/).map(x=>x.trim()).filter(Boolean)
      if(['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(row.role) && !['DESIGN','ACTUAL'].includes(row.kind)) throw Error(`${row.file.name}：请选择设计方案或实钻测斜`)
      const confirmed={association_confirmed:true,document_role:row.role,trajectory_kind:row.kind||'',document_scope:row.reviewData?.confirmed?.document_scope||row.reviewData?.proposed?.document_scope||'WELL',block:f.block.trim(),field:f.field.trim(),well:f.well.trim(),wellbore:f.wellbore.trim(),site:f.field.trim(),engineering:{block:f.block.trim(),field:f.field.trim(),well:f.well.trim(),wellbore:f.wellbore.trim(),site:f.field.trim(),trajectory_kind:row.kind||'',document_role:row.role,neighbor_candidates:neighbors},neighbor_candidates:neighbors,reference:row.reviewData?.proposed?.reference||{}}
      const field=Number(row.source?.source_field_id||row.source?.field_id||activeFieldId.value)
      row.source=await confirmSourceReview(field,row.source.source_id,{status:'CONFIRMED',confirmed,note:'上传框内批量确认解析层级'})
      row.status='CONFIRMED'
    }
    associationConfirmed.value=false
    ElMessage.success(`已确认 ${completed.value.length} 个文件的工程关联`)
  } catch(e) { error.value=e.response?.data?.msg||e.message||'确认失败' }
  finally { reviewSaving.value=false }
}
function invalid(row) {
  const node = nodeFor(row.nodeId)
  const targetError = catalogUploadError(node, row.role, ['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(row.role) ? row.kind : '')
  if (targetError) return targetError
  if (row.displayName?.length > 255 || /[\u0000-\u001f\u007f]/.test(row.displayName || '')) return '系统名称最多255个字符，不能包含控制字符'
  if (row.file.size > 256 * 1024 * 1024) return '单文件不能超过 256 MB'
  return ''
}
function parseInvalid(row) {
  const node = nodeFor(row.nodeId)
  if (!catalogFileTargetAllowed(node)) return '请选择可编辑的区块、井场、井、井眼或资料目录'
  if (row.displayName?.length > 255 || /[\u0000-\u001f\u007f]/.test(row.displayName || '')) return '系统名称最多255个字符，不能包含控制字符'
  if (row.file.size > 256 * 1024 * 1024) return '单文件不能超过 256 MB'
  return ''
}
async function submit() {
  error.value = ''
  for (const row of pending.value) { row.error = invalid(row); if (row.error) error.value = '请修改列表中的关联对象或文件用途后上传' }
  if (error.value) return
  uploading.value = true
  try {
    for (const row of pending.value) {
      row.status = 'UPLOADING'; row.error = ''
      const node = nodeFor(row.nodeId)
      try {
        const storageFieldId = node?.field_id || activeFieldId.value
        if (!storageFieldId) throw Error('当前工程范围没有可用的文件存储井场，请先建立井场')
        row.source = await uploadSurveySource(storageFieldId, row.file, { nodeId: row.nodeId, sourcePath: row.relativePath || row.file.name, documentRole: row.role, trajectoryKind: ['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(row.role) ? row.kind : '', ...(versionTarget.value ? {documentId:versionTarget.value.document_id,expectedEditionNo:versionTarget.value.latest_edition_no} : {}) })
        row.status = 'SUCCESS'
      } catch (e) { row.status = 'FAILED'; row.error = e.response?.data?.msg || e.message || '导入失败' }
    }
    if (completed.value.length === rows.value.length) {
      ElMessage.success(versionTarget.value ? completed.value[0].source.file_unchanged ? '文件内容已存在，未新增版次' : `已保存第 ${completed.value[0].source.edition_no} 版文件` : `已解析入库 ${completed.value.length} 个文件`)
      // Close and emit immediately so the caller opens the mandatory review
      // form. Waiting for a second “完成” click made parsing appear absent.
      close()
    } else error.value = '部分文件未导入成功，可修改后继续上传'
  } finally { uploading.value = false }
}
function close() {
  open.value = false
  if (completed.value.length) emit('uploaded', completed.value.map(r => ({ ...r.source, target: nodeFor(r.nodeId) })))
}
function beforeClose(done) { if (!uploading.value) { close(); done() } }
defineExpose({ show, showVersion })
</script>

<style scoped>
.target-select{width:390px;max-width:100%;margin-right:12px}.engineering-path{line-height:1.7}.file-toolbar{display:flex;align-items:center;gap:12px;margin:4px 0 16px}.file-toolbar span,small{font-size:12px;color:var(--el-text-color-secondary)}.row-path{font-size:12px;line-height:1.6;white-space:normal;margin-top:6px;color:var(--el-text-color-regular)}.full{width:100%}.error-text{color:var(--el-color-danger);line-height:1.6}.import-error,.import-progress{margin-top:14px}
.version-summary{margin-bottom:18px}.version-summary :deep(.el-descriptions__label){width:110px;white-space:nowrap}.version-summary :deep(.el-descriptions__content){overflow-wrap:anywhere}
.inline-review{margin-top:16px;padding:14px;border:1px solid var(--el-border-color);border-radius:4px;background:var(--el-fill-color-lighter)}.inline-review-title{font-weight:600;color:var(--el-text-color-primary);margin-bottom:10px}.review-scope-tip{margin-bottom:12px}.file-review-row{padding:12px 0;border-top:1px solid var(--el-border-color-lighter)}.file-review-row:first-of-type{border-top:0}.file-review-name{font-weight:600;color:var(--el-text-color-primary);margin-bottom:8px;overflow-wrap:anywhere}.inline-review-actions{display:flex;align-items:center;justify-content:flex-end;gap:18px;margin-top:12px}.inline-review-actions :deep(.el-checkbox){margin-right:auto}
@media(max-width:600px){.file-toolbar{flex-wrap:wrap}.file-toolbar span{flex:1 1 220px}:deep(.el-form-item__content){flex-wrap:wrap;gap:8px}.target-select{margin-right:0;min-width:0}}
</style>
