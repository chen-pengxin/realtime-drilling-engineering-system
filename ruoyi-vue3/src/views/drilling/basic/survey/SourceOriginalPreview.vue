<template>
  <el-dialog v-model="visible" title="原件预览" width="min(1100px, calc(100vw - 24px))" append-to-body class="drilling-dialog" @closed="clear">
    <div v-loading="loading" class="preview-body">
      <iframe v-if="kind === 'pdf' && url" :src="url" title="PDF 原件" class="pdf-frame" />
      <pre v-else-if="kind === 'text'" class="text-frame">{{ text }}</pre>
      <el-empty v-else description="该格式保留原件字节；请使用下载原件查看完整排版、图片、公式和工作表。" />
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer><el-button @click="visible = false">关闭</el-button><el-button type="primary" icon="Download" :disabled="!blob" @click="download">下载原件</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadSourceOriginal, downloadBlob } from '@/api/drillingRegistry'

const props = defineProps({ fieldId: Number })
const visible = ref(false), loading = ref(false), error = ref(''), blob = ref(), url = ref(''), text = ref(''), kind = ref('binary'), source = ref()
const textSuffixes = new Set(['txt', 'csv', 'tsv', 'dat', 'asc', 'mwd', 'survey', 'xml', 'witsml'])
function suffix(name) { const value=String(name || '');return value.includes('.') ? value.split('.').pop().toLowerCase() : '' }
function clear() { if (url.value) URL.revokeObjectURL(url.value); url.value = ''; blob.value = undefined; text.value = ''; source.value = undefined }
async function show(row) {
  clear(); source.value = row; visible.value = true; loading.value = true; error.value = ''
  try {
    blob.value = await downloadSourceOriginal(Number(row.source_field_id || row.field_id) || props.fieldId, row.source_id)
    const ext = suffix(row.original_name)
    if (ext === 'pdf') { kind.value = 'pdf'; url.value = URL.createObjectURL(blob.value) }
    else if (textSuffixes.has(ext) || !ext) { kind.value = 'text'; let metadata={};try{metadata=typeof row.metadata_json==='string'?JSON.parse(row.metadata_json):row.metadata_json||{}}catch(_){};let encoding=metadata.encoding||'utf-8';if(encoding==='utf-8-sig')encoding='utf-8';try{text.value=new TextDecoder(encoding).decode(await blob.value.arrayBuffer())}catch(_){text.value=await blob.value.text()} }
    else kind.value = 'binary'
  } catch (e) { error.value = e?.message || '原件读取失败'; ElMessage.error(error.value) }
  finally { loading.value = false }
}
function download() { if (blob.value) downloadBlob(blob.value, source.value?.original_name || '工程资料') }
defineExpose({ show })
</script>

<style scoped>
.preview-body{min-height:420px;background:var(--el-fill-color-lighter);display:flex;align-items:stretch;justify-content:center}.pdf-frame{width:100%;height:min(72vh,760px);border:0;background:#fff}.text-frame{width:100%;max-height:72vh;overflow:auto;margin:0;padding:16px;white-space:pre-wrap;word-break:break-all;text-align:left;font:12px/1.65 var(--el-font-family);color:var(--el-text-color-primary);background:var(--el-bg-color)}
</style>
