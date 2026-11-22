<template>
  <DrillingPageFrame title="文件与审计">
    <el-tabs v-model="activeTab">
      <el-tab-pane v-if="canFiles" label="系统文件" name="files">
        <el-form inline @submit.prevent="load">
          <el-form-item label="文件类别"><el-select v-model="category" style="width:160px"><el-option v-for="item in categories" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="文件名称"><el-input v-model="search" clearable placeholder="请输入文件名称" /></el-form-item>
          <el-form-item><el-button type="primary" icon="Search" @click="load">搜索</el-button><el-button icon="Refresh" @click="category='ALL';search='';load()">重置</el-button></el-form-item>
        </el-form>
        <div class="drilling-toolbar"><el-button type="primary" plain icon="Upload" :disabled="!fieldId" @click="fileInput?.click()">上传文件</el-button><input ref="fileInput" hidden type="file" @change="upload" /></div>
        <el-table :data="visibleFiles" v-loading="loading">
          <el-table-column label="系统名称" min-width="240" show-overflow-tooltip><template #default="{row}"><el-button link type="primary" @click="detail=row">{{ fileDisplayName(row) }}</el-button></template></el-table-column>
          <el-table-column label="类别" width="100"><template #default="{row}">{{ categoryName(row.file_category) }}</template></el-table-column>
          <el-table-column label="模板" width="80"><template #default="{row}"><el-tag :type="row.is_template==='1'?'success':'info'">{{ row.is_template==='1'?'是':'否' }}</el-tag></template></el-table-column>
          <el-table-column prop="version_no" label="版本" width="80" />
          <el-table-column label="大小" width="110"><template #default="{row}">{{ fileSize(row.file_size) }}</template></el-table-column>
          <el-table-column prop="create_time" label="上传时间" min-width="170" />
          <el-table-column label="操作" width="300" fixed="right"><template #default="{row}"><el-button v-hasPermi="['drilling:file:upload']" link type="primary" @click="editFileDisplayName(fieldId,row,load)">修改系统名称</el-button><el-button link type="primary" icon="Download" @click="download(row)">下载</el-button><el-button link type="danger" icon="Delete" @click="remove(row)">归档</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane v-if="canFiles" label="文件操作记录" name="audit">
        <div class="drilling-toolbar"><el-button icon="Refresh" @click="loadAudit">刷新</el-button></div>
        <el-table :data="auditRows"><el-table-column prop="audit_id" label="编号" width="80" /><el-table-column prop="action" label="动作" width="150" /><el-table-column prop="resource_type" label="资源" width="150" /><el-table-column prop="resource_id" label="资源编号" width="110" /><el-table-column prop="detail_json" label="详情" min-width="260" show-overflow-tooltip /><el-table-column prop="create_time" label="时间" min-width="170" /></el-table>
      </el-tab-pane>
      <el-tab-pane v-if="canFiles" label="参考资料" name="references" lazy>
        <el-alert title="参考资料为系统级原件，不属于工程树。SY/T 5435-2012 附录 A.1–A.6 为扫描版空白表格；此处展示原文表头、单位和注释，预览或下载查看原件。" type="info" :closable="false" class="reference-note" />
        <el-table :data="referenceRows" v-loading="referenceLoading" border>
          <el-table-column type="expand"><template #default="{row}"><el-table :data="appendixTables(row)" size="small" border><el-table-column prop="code" label="表号" width="90"/><el-table-column prop="title" label="表名" min-width="210"/><el-table-column prop="source_title" label="原文表名" min-width="320"/><el-table-column prop="pdf_page" label="PDF页码" width="100"/><el-table-column label="原文表头与单位" min-width="430"><template #default="scope"><el-tag v-for="field in scope.row.source_fields" :key="field" class="field-tag" effect="plain">{{ field }}</el-tag></template></el-table-column><el-table-column label="中文字段" min-width="360"><template #default="scope"><el-tag v-for="field in scope.row.fields" :key="field" class="field-tag" effect="plain">{{ field }}</el-tag></template></el-table-column><el-table-column label="原文注释" min-width="440"><template #default="scope"><div v-for="note in scope.row.source_notes" :key="note" class="source-note">{{ note }}</div><span v-if="!scope.row.source_notes?.length">—</span></template></el-table-column></el-table></template></el-table-column>
          <el-table-column prop="title" label="资料名称" min-width="260" />
          <el-table-column prop="standard_no" label="标准编号" width="160" />
          <el-table-column label="附录表" min-width="340"><template #default="{row}">{{ appendixLabels(row) }}</template></el-table-column>
          <el-table-column prop="page_count" label="页数" width="80" />
          <el-table-column prop="sha256" label="SHA-256" min-width="320" show-overflow-tooltip />
          <el-table-column label="操作" width="280" fixed="right"><template #default="{row}"><el-button link type="primary" @click="referenceContent.showReference(row)">查看表格</el-button><el-button link type="primary" icon="View" @click="previewReference(row)">预览原件</el-button><el-button link type="primary" icon="Download" @click="downloadReference(row)">下载原件</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane v-if="user.allowedModules.includes('history')" label="任务历史" name="history" lazy><EmbeddedDrilling><TaskHistory /></EmbeddedDrilling></el-tab-pane>
      <el-tab-pane v-if="user.allowedModules.includes('reports')" label="报告归档" name="reports" lazy><EmbeddedDrilling><ReportArchive /></EmbeddedDrilling></el-tab-pane>
    </el-tabs>
    <ResizableDialog :model-value="Boolean(detail)" title="文件详情" width="620px" class="drilling-dialog" @close="detail=null">
      <el-descriptions v-if="detail" :column="1" border><el-descriptions-item label="系统名称">{{ fileDisplayName(detail) }}</el-descriptions-item><el-descriptions-item label="原始文件名">{{ detail.original_name }}</el-descriptions-item><el-descriptions-item label="类别">{{ categoryName(detail.file_category) }}</el-descriptions-item><el-descriptions-item label="大小">{{ fileSize(detail.file_size) }}</el-descriptions-item><el-descriptions-item label="版本">{{ detail.version_no }}</el-descriptions-item><el-descriptions-item label="SHA-256"><span style="overflow-wrap:anywhere">{{ detail.sha256 }}</span></el-descriptions-item><el-descriptions-item label="上传时间">{{ detail.create_time }}</el-descriptions-item></el-descriptions>
      <template #footer><el-button @click="detail=null">关 闭</el-button></template>
    </ResizableDialog>
    <ResizableDialog v-model="referencePreviewVisible" title="参考资料原件" width="min(1100px, calc(100vw - 24px))" class="drilling-dialog" @closed="clearReferencePreview">
      <iframe v-if="referencePreviewUrl" :src="referencePreviewUrl" title="参考资料原件" class="reference-preview-frame" />
      <el-empty v-else description="原件读取失败" />
      <template #footer><el-button @click="referencePreviewVisible=false">关闭</el-button></template>
    </ResizableDialog>
    <DocumentContent ref="referenceContent" />
  </DrillingPageFrame>
</template>

<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import {defineAsyncComponent} from 'vue'
import DocumentContent from './survey/DocumentContent.vue'
const referenceContent=ref()
import useUserStore from '@/store/modules/user'
import EmbeddedDrilling from '../layout/EmbeddedDrilling.vue'
import {useManagementTabs} from '../composables/useManagementTabs'
const TaskHistory=defineAsyncComponent(()=>import('../centers/history/index.vue'))
const ReportArchive=defineAsyncComponent(()=>import('../centers/reports/index.vue'))
const user=useUserStore(),canFiles=computed(()=>user.allowedModules.includes('files'))
const {active:activeTab}=useManagementTabs([{name:'files',label:'系统文件'},{name:'audit',module:'files',label:'文件操作记录'},{name:'references',module:'files',label:'参考资料'},{name:'history',label:'任务历史'},{name:'reports',label:'报告归档'}])

import { computed, onMounted, ref, watch } from 'vue'
import { fileDisplayName } from '@/utils/fileNames'
import { editFileDisplayName } from '../composables/fileDisplayName'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteFieldFile, downloadFieldFile, listFieldAudit, listFieldFiles, uploadFieldFile } from '@/api/drillingField'
import { downloadReferenceMaterial, listReferenceMaterials } from '@/api/drillingReference'
import DrillingPageFrame from '../layout/DrillingPageFrame.vue'
import { useDrillingContext } from '../composables/useDrillingContext'
const detail=ref(null),referencePreviewVisible=ref(false),referencePreviewUrl=ref('')
const search=ref(''), loading=ref(false)
const visibleFiles=computed(()=>files.value.filter(row=>`${fileDisplayName(row)} ${row.original_name||''}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const categoryName=value=>categories.find(item=>item.value===value)?.label||value
const fileSize=value=>Number(value)>=1048576?`${(Number(value)/1048576).toFixed(1)} MB`:Number(value)>=1024?`${(Number(value)/1024).toFixed(1)} KB`:`${Number(value)||0} B`
const { fieldId, loadFields }=useDrillingContext();const files=ref([]);const auditRows=ref([]);const referenceRows=ref([]),referenceLoading=ref(false);const category=ref('ALL');const fileInput=ref(null);const categories=[{value:'ALL',label:'全部'},{value:'DATASET',label:'数据集'},{value:'SURVEY',label:'测斜'},{value:'TEMPLATE',label:'模板'},{value:'MODEL',label:'模型'},{value:'REPORT',label:'报告'},{value:'OTHER',label:'其他'}]
async function load(){
  files.value=[]
  if(!fieldId.value||!canFiles.value)return
  loading.value=true
  try {
    const query=category.value==='ALL'?{}:{category:category.value}
    const [regular,templates]=await Promise.all([listFieldFiles(fieldId.value,query),category.value==='ALL'||category.value==='TEMPLATE'?listFieldFiles(fieldId.value,{...query,templates:true}):Promise.resolve([])])
    files.value=[...new Map([...(regular||[]),...(templates||[])].map(row=>[row.file_id,row])).values()]
  } finally { loading.value=false }
}
async function loadAudit(){if(fieldId.value&&canFiles.value)auditRows.value=await listFieldAudit(fieldId.value)||[]}
async function loadReferences(){if(!canFiles.value)return;referenceLoading.value=true;try{referenceRows.value=await listReferenceMaterials()||[]}finally{referenceLoading.value=false}}
function appendixTables(row){try{return typeof row.appendix_tables_json==='string'?JSON.parse(row.appendix_tables_json):row.appendix_tables_json||[]}catch(_){return []}}
function appendixLabels(row){return appendixTables(row).map(item=>`${item.code} ${item.title}`).join('；')||'—'}
async function downloadReference(row){try{const blob=await downloadReferenceMaterial(row.reference_id);const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=row.original_name||`${row.standard_no||'参考资料'}.pdf`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}catch(e){ElMessage.error(e.message||'下载失败')}}
async function previewReference(row){try{clearReferencePreview();const blob=await downloadReferenceMaterial(row.reference_id);referencePreviewUrl.value=URL.createObjectURL(blob);referencePreviewVisible.value=true}catch(e){ElMessage.error(e.message||'原件读取失败')}}
function clearReferencePreview(){if(referencePreviewUrl.value)URL.revokeObjectURL(referencePreviewUrl.value);referencePreviewUrl.value=''}
async function upload(e){const file=e.target.files?.[0];e.target.value='';if(!file||!fieldId.value)return;try{const isTemplate=category.value==='TEMPLATE';if(isTemplate&&!file.name.toLowerCase().endsWith('.py'))throw new Error('训练模板必须是 .py 文件');const form=new FormData();form.append('file',file);form.append('category',category.value==='ALL'?'OTHER':category.value);form.append('template',String(isTemplate));await uploadFieldFile(fieldId.value,form);await load();ElMessage.success('文件已上传')}catch(err){ElMessage.error(err.message||'上传失败')}}
async function download(row){const blob=await downloadFieldFile(fieldId.value,row.file_id);const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=row.original_name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
async function remove(row){try{await ElMessageBox.confirm(`确认归档文件“${row.original_name}”吗？`,'归档确认',{type:'warning'});await deleteFieldFile(fieldId.value,row.file_id);await load();ElMessage.success('文件已归档')}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e.message||'归档失败')}}
watch([fieldId,category],async()=>{await load();await loadAudit();await loadReferences()});onMounted(async()=>{if(!fieldId.value)await loadFields();await load();await loadAudit();await loadReferences()})
</script>

<style scoped>.panel{padding:16px;border:1px solid var(--el-border-color-light);background:var(--el-bg-color)}.panel-title{display:flex;justify-content:space-between;align-items:center;color:var(--el-text-color-primary);font-weight:600;margin-bottom:14px}.panel-title .el-button{margin-left:8px}.audit{margin-top:14px}.reference-note{margin-bottom:16px}.field-tag{margin:2px 5px 2px 0}.source-note{white-space:normal;line-height:1.5;margin:3px 0}.reference-preview-frame{width:100%;height:min(76vh,820px);border:0;background:#fff}
</style>
