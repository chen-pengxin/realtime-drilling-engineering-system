<template>
  <el-dialog v-model="open" :title="versionTarget ? '导入文件版次' : '导入来源文件'" width="min(1120px, calc(100vw - 32px))" append-to-body :close-on-click-modal="false" :before-close="beforeClose">
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
      <el-button icon="Plus" :disabled="uploading || !defaultNodeId || !!versionTarget && !!completed.length" @click="fileInput.click()">选择文件</el-button>
      <span>{{ versionTarget ? '选择一份替换文件，保存后请核对解析结果。' : '本批文件逐项显示归属，可分别修改；原件入库后仍需核对解析结果。' }}</span>
    </div>
    <el-table :data="rows" border max-height="430" row-key="id">
      <el-table-column label="原始文件名" min-width="190"><template #default="s"><div>{{ s.row.file.name }}</div><small>{{ sizeText(s.row.file.size) }}</small></template></el-table-column>
      <el-table-column label="系统名称" min-width="190"><template #default="s"><span>{{ s.row.displayName }}</span></template></el-table-column>
      <el-table-column label="关联层级" min-width="300"><template #default="s">
        <el-tree-select v-model="s.row.nodeId" :data="targetTree" node-key="node_id" :props="treeProps" check-strictly filterable :render-after-expand="false" :disabled="uploading || s.row.status==='SUCCESS' || !!versionTarget" @change="changeTarget(s.row)" class="full" />
        <div class="row-path">{{ nodePath(s.row.nodeId) }}</div>
      </template></el-table-column>
      <el-table-column label="文件用途" width="150"><template #default="s"><el-select v-model="s.row.role" :disabled="uploading || s.row.status==='SUCCESS' || !!versionTarget"><el-option v-for="(label,key) in documentRoles" :key="key" :label="label" :value="key" /></el-select></template></el-table-column>
      <el-table-column label="轨迹类别" width="140"><template #default="s">
        <el-select v-if="['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(s.row.role)" v-model="s.row.kind" :disabled="uploading || s.row.status==='SUCCESS' || !!versionTarget?.trajectory_kind || ['PLAN','SURVEY'].includes(nodeFor(s.row.nodeId)?.node_type)" clearable placeholder="选择用途"><el-option label="设计方案" value="DESIGN" /><el-option label="实钻测斜" value="ACTUAL" /></el-select><span v-else>—</span>
      </template></el-table-column>
      <el-table-column label="导入状态" min-width="150"><template #default="s"><el-tag v-if="s.row.status==='SUCCESS'" type="success">{{ versionTarget ? (s.row.source?.file_unchanged ? '文件内容未变化' : `已保存第 ${s.row.source?.edition_no} 版`) : '已关联入库' }}</el-tag><span v-else-if="s.row.status==='UPLOADING'">正在解析入库</span><span v-else-if="s.row.error" class="error-text">{{ s.row.error }}</span><span v-else>待上传</span></template></el-table-column>
      <el-table-column label="操作" width="70"><template #default="s"><el-button v-if="s.row.status!=='SUCCESS'" link type="danger" :disabled="uploading" @click="removeRow(s.row)">移除</el-button></template></el-table-column>
    </el-table>
    <div v-if="error" class="error-text import-error">{{ error }}</div>
    <el-progress v-if="uploading" :percentage="progress" class="import-progress" />
    <template #footer><el-button :disabled="uploading" @click="close">{{ completed.length ? '完成' : '取消' }}</el-button><el-button type="primary" :loading="uploading" :disabled="!pending.length" @click="submit">{{ versionTarget ? '保存文件版次' : completed.length ? '继续上传' : '上传并关联' }}</el-button></template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadSurveySource } from '@/api/drillingRegistry'
import { libraryDocument } from '@/api/engineeringLibrary'
import { fileDisplayName } from '@/utils/fileNames'
import { catalogFileTargetAllowed, catalogUploadError } from '@/utils/engineeringCatalog'

const props = defineProps({ nodes: { type: Array, default: () => [] }, documentRoles: Object })
const emit = defineEmits(['uploaded'])
const open = ref(false), uploading = ref(false), defaultNodeId = ref(null), rows = ref([]), fileInput = ref(), error = ref(''), activeFieldId = ref(null)
const versionTarget = ref(null)
const treeProps = { label: 'label', children: 'children', disabled: 'disabled' }
const nodeMap = computed(() => new Map(props.nodes.map(n => [Number(n.node_id), n])))
const targetTree = computed(() => {
  const available = props.nodes.filter(n => n.node_type === 'BLOCK' || Number(n.field_id) === Number(activeFieldId.value))
  const nodes = available.map(n => ({ ...n, children: [], disabled: !catalogFileTargetAllowed(n) }))
  const map = new Map(nodes.map(n => [Number(n.node_id), n])), roots = []
  nodes.forEach(n => { const parent = map.get(Number(n.parent_id)); if (parent) parent.children.push(n); else roots.push(n) })
  return roots.filter(n => n.node_type !== 'BLOCK' || n.children.length)
})
const nodeFor = id => nodeMap.value.get(Number(id))
const nodePath = id => nodeFor(id)?.engineering_path || ''
function generatedName(node, role, kind, edition=1) {
  const path=String(node?.engineering_path||'').split(' / ').filter(Boolean)
  const well=path.at(-2) || node?.well_name || '井'
  const type=kind==='DESIGN'?'设计方案':kind==='ACTUAL'?'实测测斜':role==='ENGINEERING_REPORT'?'工程报告':'工程资料'
  return `${well}_${type}_${edition}`
}
const completed = computed(() => rows.value.filter(r => r.status === 'SUCCESS'))
const pending = computed(() => rows.value.filter(r => r.status !== 'SUCCESS'))
const progress = computed(() => Math.round(100 * rows.value.filter(r => ['SUCCESS','FAILED'].includes(r.status)).length / Math.max(rows.value.length,1)))
const sizeText = size => size >= 1048576 ? `${(size / 1048576).toFixed(1)} MB` : `${Math.ceil(size / 1024)} KB`
let serial = 0
function show(node) {
  versionTarget.value = null
  activeFieldId.value = node?.field_id
  defaultNodeId.value = catalogFileTargetAllowed(node) ? Number(node.node_id) : null
  rows.value = []; error.value = ''; open.value = true
}
async function showVersion(row) {
  try {
    const node=nodeFor(row.node_id)
    if(!node)throw Error('请刷新工程目录后重试')
    const document=await libraryDocument(node.field_id,row.document_id)
    show(node);versionTarget.value=document
  } catch(e) { ElMessage.error(e.response?.data?.msg || e.message) }
}
function changeTarget(row) { const type = nodeFor(row.nodeId)?.node_type; row.kind = type === 'PLAN' ? 'DESIGN' : type === 'SURVEY' ? 'ACTUAL' : ''; row.error = '' }
function applyTarget() { pending.value.forEach(row => { row.nodeId = defaultNodeId.value; changeTarget(row) }) }
function inferRole(file) { const ext = file.name.split('.').pop().toLowerCase(); return ['csv','xls','xlsx','txt','tsv','dat','asc','mwd','xml','witsml'].includes(ext) ? 'TRAJECTORY_DATA' : ['pdf','doc','docx'].includes(ext) ? 'ENGINEERING_REPORT' : ['zip','rar','7z'].includes(ext) ? 'ARCHIVE' : 'REFERENCE' }
function selectFiles(event) {
  const files = [...(event.target.files || [])]; event.target.value = ''
  if(versionTarget.value){if(files.length!==1)return ElMessage.warning('更新文件时请选择一个文件');rows.value=[]}
  if (files.length + rows.value.length > 200) return ElMessage.warning('每批最多 200 个文件')
  for (const file of files) {
    if (rows.value.some(r => r.file.name === file.name && r.file.size === file.size && r.file.lastModified === file.lastModified)) continue
    const role=versionTarget.value?.document_role || inferRole(file); const kind=versionTarget.value?.trajectory_kind || ''; const row = { id: ++serial, file, displayName: versionTarget.value?.display_name || generatedName(nodeFor(defaultNodeId.value),role,kind,versionTarget.value?.latest_edition_no?Number(versionTarget.value.latest_edition_no)+1:1), nodeId: defaultNodeId.value, role, kind, status: 'PENDING', error: '' }; changeTarget(row);if(versionTarget.value?.trajectory_kind)row.kind=versionTarget.value.trajectory_kind; rows.value.push(row)
  }
}
function removeRow(row) { rows.value = rows.value.filter(r => r.id !== row.id) }
function invalid(row) {
  const node = nodeFor(row.nodeId)
  const targetError = catalogUploadError(node, row.role, ['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(row.role) ? row.kind : '')
  if (targetError) return targetError
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
        row.source = await uploadSurveySource(node.field_id, row.file, { nodeId: row.nodeId, documentRole: row.role, trajectoryKind: ['TRAJECTORY_DATA','TRAJECTORY_REPORT'].includes(row.role) ? row.kind : '', ...(versionTarget.value ? {documentId:versionTarget.value.document_id,expectedEditionNo:versionTarget.value.latest_edition_no} : {}) })
        row.status = 'SUCCESS'
      } catch (e) { row.status = 'FAILED'; row.error = e.response?.data?.msg || e.message || '导入失败' }
    }
    if (completed.value.length === rows.value.length) ElMessage.success(versionTarget.value ? completed.value[0].source.file_unchanged ? '文件内容已存在，未新增版次' : `已保存第 ${completed.value[0].source.edition_no} 版文件` : `已关联入库 ${completed.value.length} 个文件`)
    else error.value = '部分文件未导入成功，可修改后继续上传'
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
@media(max-width:600px){.file-toolbar{flex-wrap:wrap}.file-toolbar span{flex:1 1 220px}:deep(.el-form-item__content){flex-wrap:wrap;gap:8px}.target-select{margin-right:0;min-width:0}}
</style>
