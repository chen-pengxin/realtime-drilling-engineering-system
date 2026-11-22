<template>
  <el-dialog v-model="visible" title="原件预览" width="min(1100px, calc(100vw - 24px))" append-to-body class="drilling-dialog" @closed="clear">
    <div v-loading="loading" class="preview-body">
      <SourcePdfReader v-if="kind === 'pdf' && url" :url="url" />
      <pre v-else-if="kind === 'text'" class="text-frame">{{ text }}</pre>
      <object v-else-if="kind === 'binary' && url" :data="url" :type="mimeType || undefined" class="binary-frame" aria-label="原始文档">
        <div class="binary-fallback">当前浏览器没有可用的原件阅读插件，请点击“下载原件”后使用本机办公软件打开。</div>
      </object>
      <el-empty v-else description="原件预览不可用" />
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer><el-button @click="visible = false">关闭</el-button><el-button type="primary" icon="Download" :disabled="!blob" @click="download">下载原件</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadSourceOriginal, downloadBlob } from '@/api/drillingRegistry'
import request from '@/utils/request'
import SourcePdfReader from './SourcePdfReader.vue'

const props = defineProps({ fieldId: Number })
const visible = ref(false), loading = ref(false), error = ref(''), blob = ref(), url = ref(''), text = ref(''), kind = ref('binary'), mimeType = ref(''), source = ref()
const textSuffixes = new Set(['txt', 'csv', 'tsv', 'dat', 'asc', 'mwd', 'survey', 'xml', 'witsml'])
function suffix(name) { const value=String(name || '');return value.includes('.') ? value.split('.').pop().toLowerCase() : '' }
function clear() { if (url.value) URL.revokeObjectURL(url.value); url.value = ''; blob.value = undefined; text.value = ''; mimeType.value = ''; source.value = undefined }
async function show(row) {
  clear(); source.value = row; visible.value = true; loading.value = true; error.value = ''
  try {
    blob.value = await downloadSourceOriginal(Number(row.source_field_id || row.field_id) || props.fieldId, row.source_id)
    const ext = suffix(row.original_name)
    mimeType.value = blob.value.type || ''
    if (ext === 'pdf') { kind.value = 'pdf'; url.value = URL.createObjectURL(blob.value) }
    else if (textSuffixes.has(ext) || !ext) { kind.value = 'text'; let metadata={};try{metadata=typeof row.metadata_json==='string'?JSON.parse(row.metadata_json):row.metadata_json||{}}catch(_){};let encoding=metadata.encoding||'utf-8';if(encoding==='utf-8-sig')encoding='utf-8';try{text.value=new TextDecoder(encoding).decode(await blob.value.arrayBuffer())}catch(_){text.value=await blob.value.text()} }
    else if(['doc','docx','xls','xlsx','wps','rtf','odt','ods'].includes(ext)){
      const rendered=await request({url:`/api/drilling/fields/${Number(row.source_field_id||row.field_id)||props.fieldId}/registry/sources/${row.source_id}/original-preview`,responseType:'blob',timeout:120000});kind.value='pdf';url.value=URL.createObjectURL(rendered)
    }
    else { kind.value = 'binary'; url.value = URL.createObjectURL(blob.value) }
  } catch (e) { error.value = await previewError(e); ElMessage.error(error.value) }
  finally { loading.value = false }
}
async function previewError(error){
 let value=error?.response?.data
 if(value instanceof Blob){try{value=JSON.parse(await value.text())}catch{value=null}}
 let message=value?.msg||value?.detail||error?.message||'原件读取失败'
 for(let i=0;i<2;i++){if(typeof message!=='string')break;try{const parsed=JSON.parse(message);message=parsed.detail||parsed.msg||message}catch{break}}
 return String(message)
}
function download() { if (blob.value) downloadBlob(blob.value, source.value?.original_name || '工程资料') }
defineExpose({ show })
</script>

<style scoped>
.preview-body{min-height:420px;background:var(--el-fill-color-lighter);display:flex;align-items:stretch;justify-content:center}.pdf-frame,.binary-frame{width:100%;height:min(72vh,760px);border:0;background:#fff}.binary-fallback{display:flex;align-items:center;justify-content:center;height:100%;padding:24px;text-align:center;color:var(--el-text-color-secondary)}.text-frame{width:100%;max-height:72vh;overflow:auto;margin:0;padding:16px;white-space:pre-wrap;word-break:break-all;text-align:left;font:12px/1.65 var(--el-font-family);color:var(--el-text-color-primary);background:var(--el-bg-color)}
</style>
