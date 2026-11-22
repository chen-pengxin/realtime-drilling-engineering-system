<template>
  <el-dialog v-model="open" title="来源资料库" width="1000px" append-to-body class="drilling-dialog">
    <el-form inline @submit.prevent="load">
      <el-form-item label="文件名称"><el-input v-model="query.keyword" clearable @keyup.enter="search" /></el-form-item>
      <el-form-item label="解析状态"><el-select v-model="query.status" clearable style="width:160px"><el-option v-for="(label,key) in statuses" :key="key" :value="key" :label="label" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="search">查询</el-button></el-form-item>
    </el-form>
    <el-table :data="rows" v-loading="loading" border max-height="500">
      <el-table-column type="expand">
        <template #default="s">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="解析诊断">{{ displayDiagnostics(s.row) }}</el-descriptions-item>
            <el-descriptions-item label="原始文件名">{{ s.row.original_name }}</el-descriptions-item>
            <el-descriptions-item label="工作表"><span v-if="workbookSheets(s.row)">{{ workbookSheets(s.row) }}</span><span v-else>—</span></el-descriptions-item>
            <el-descriptions-item label="正文摘录"><pre v-if="textExcerpt(s.row)" class="source-excerpt">{{ textExcerpt(s.row) }}</pre><span v-else>该资料未提取出可预览正文，请下载原件查看。</span></el-descriptions-item>
          </el-descriptions>
        </template>
      </el-table-column>
      <el-table-column label="系统名称" min-width="260" show-overflow-tooltip><template #default="s">{{ displayFileName(s.row) }}</template></el-table-column>
      <el-table-column label="格式" width="100"><template #default="{row}">{{formats.find(x=>x.dictValue===row.source_format?.toUpperCase())?.dictLabel || row.source_format}}</template></el-table-column>
      <el-table-column label="解析状态" width="110"><template #default="s">{{ statuses[s.row.parse_status] || s.row.parse_status }}</template></el-table-column>
      <el-table-column label="资料状态" width="105"><template #default="s"><el-tag :type="s.row.review_status === 'CONFIRMED' ? 'success' : 'warning'">{{ reviewStates.find(x=>x.dictValue===(s.row.review_status==='CONFIRMED'?'CONFIRMED':'REVIEW_REQUIRED'))?.dictLabel || s.row.review_status }}</el-tag></template></el-table-column>
      <el-table-column prop="create_time" label="入库时间" width="175" />
      <el-table-column label="操作" width="470"><template #default="s"><el-button link type="primary" @click="choose(s.row)">选择资料</el-button><el-button link type="primary" @click="preview.show(s.row)">查看表格</el-button><el-button link type="primary" @click="originalPreview.show(s.row)">原件预览</el-button><el-button v-hasPermi="['drilling:well:survey:import']" link type="primary" @click="review.show(s.row)">核对资料</el-button><el-button v-hasPermi="['drilling:well:survey:import']" link type="primary" @click="mapping.show(s.row)">字段映射</el-button><el-button link @click="download(s.row)">下载原件</el-button></template></el-table-column>
    </el-table>
    <pagination v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
  </el-dialog>
  <SourceMapping ref="mapping" :field-id="fieldId" @mapped="mapped" />
  <DocumentContent ref="preview" :field-id="fieldId" @parsed="load" />
  <SourceOriginalPreview ref="originalPreview" :field-id="fieldId" />
  <SourceReview ref="review" :field-id="fieldId" @confirmed="confirmed" />
</template>

<script setup>
import {useDisplayDictionary} from '@/views/operations/useDisplayDictionary'
const formats=useDisplayDictionary('file_format'), reviewStates=useDisplayDictionary('review_state')
import { reactive, ref } from 'vue'
import { fileDisplayName } from '@/utils/fileNames'
import { ElMessage } from 'element-plus'
import { registry, downloadSourceOriginal, downloadBlob } from '@/api/drillingRegistry'
import SourceMapping from './SourceMapping.vue'
import DocumentContent from './DocumentContent.vue'
import SourceOriginalPreview from './SourceOriginalPreview.vue'
import SourceReview from './SourceReview.vue'

const props = defineProps({ fieldId: Number })
const emit = defineEmits(['select'])
const open = ref(false), rows = ref([]), total = ref(0), loading = ref(false)
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '' })
const review = ref(), mapping = ref(), preview = ref(), originalPreview = ref(), reviewQueue = ref([])
const statuses = { PARSED: '已解析', REVIEW_REQUIRED: '待复核', ATTACHMENT: '归档附件', FAILED: '解析失败' }
const hiddenRoots = ['采气二厂煤岩气完井资料+设计（11口）', '采气二厂煤岩气完井资料+设计(11口)', 'Volve数据集', 'well_control_iscwsa']
function cleanText(value) { let text = String(value || '').replaceAll('\\', '/'); for (const root of hiddenRoots) text = text.replaceAll(root, ''); return text }
const displayFileName = fileDisplayName
function displaySourcePath(row) { return cleanText(row?.source_path || '').split('/').filter(Boolean).pop() || '来源文件' }
function displayDiagnostics(row) { return cleanText(row?.diagnostics_json || '无') }
function sourceMetadata(row) { try { return typeof row?.metadata_json === 'string' ? JSON.parse(row.metadata_json || '{}') : (row?.metadata_json || {}) } catch (_) { return {} } }
function workbookSheets(row) { return (sourceMetadata(row).workbook_sheets || []).join('、') }
function textExcerpt(row) { const text = String(sourceMetadata(row).text_excerpt || '').trim(); return text.length > 6000 ? `${text.slice(0, 6000)}\n……（仅显示前 6000 字符）` : text }
function mapped(row) { choose(row) }
async function load() { if (!props.fieldId) return; loading.value = true; try { const r = await registry(props.fieldId, '/sources', 'get', undefined, { ...query, includeAccessible: true }); rows.value = r.rows || []; total.value = r.total || 0 } catch (e) { ElMessage.error(e.message) } finally { loading.value = false } }
function search() { query.pageNum = 1; load() }
function show() { reviewQueue.value = []; open.value = true; search() }
function choose(row) { emit('select', row); open.value = false }
async function nextReview() { while (reviewQueue.value.length) { const row = reviewQueue.value[0]; try { const current = await registry(Number(row.source_field_id || row.field_id) || props.fieldId, `/sources/${row.source_id}/review`); if (current.status === 'CONFIRMED') { reviewQueue.value.shift(); continue } } catch (_) {} review.value.show(row); return } }
async function showReviewQueue(sources = []) { reviewQueue.value = sources.filter(x => Number(x?.source_id ?? x)).map(x => typeof x === 'object' ? x : { source_id: Number(x) }); open.value = false; await nextReview() }
async function confirmed() { if (reviewQueue.value.length) reviewQueue.value.shift(); if(open.value) await load(); await nextReview() }
async function download(row) { try { downloadBlob(await downloadSourceOriginal(Number(row.source_field_id || row.field_id) || props.fieldId, row.source_id), row.original_name) } catch (e) { ElMessage.error(e.message) } }
defineExpose({ show, showReviewQueue })
</script>

<style scoped>
.source-excerpt { max-height: 240px; margin: 0; overflow: auto; white-space: pre-wrap; word-break: break-all; font: 12px/1.6 var(--el-font-family); color: var(--el-text-color-regular); background: var(--el-fill-color-lighter); padding: 8px }
</style>
