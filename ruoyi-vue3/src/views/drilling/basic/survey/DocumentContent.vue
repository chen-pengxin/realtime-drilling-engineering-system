<template>
  <el-dialog v-model="visible" title="文档内容" width="min(1320px, calc(100vw - 24px))" append-to-body class="drilling-dialog document-content-dialog" @closed="close">
    <div class="document-toolbar">
      <span class="original-name">{{ manifest.original_name || source?.original_name }}</span>
      <el-select v-if="manifest.versions?.length" v-model="parseId" aria-label="解析版次" style="width:270px" @change="loadManifest">
        <el-option v-for="v in manifest.versions" :key="v.parse_id" :value="Number(v.parse_id)" :label="`${v.parser_version} · ${String(v.create_time).replace('T',' ')}`" />
      </el-select>
      <el-button v-if="!readOnly" v-hasPermi="['drilling:well:survey:import']" icon="Refresh" :loading="parsing" :disabled="loading" @click="reparse">重新解析</el-button>
    </div>
    <div v-loading="loading" class="document-workspace">
      <aside class="document-directory">
        <el-input v-model="directoryQuery" placeholder="查找章节、工作表" clearable aria-label="查找章节、工作表" />
        <el-scrollbar height="560px">
          <el-tree :data="directory" node-key="id" default-expand-all :expand-on-click-node="false" highlight-current :current-node-key="sectionId" @node-click="selectSection">
            <template #default="{ data }"><span class="document-tree-label" :title="data.label">{{ data.label }}</span></template>
          </el-tree>
        </el-scrollbar>
      </aside>
      <main v-loading="sectionLoading" class="document-body">
        <template v-if="section">
          <div class="document-section-toolbar">
            <strong>{{ section.title }}</strong>
            <span class="source-location">{{ location }}</span>
            <el-button v-if="section.asset && section.type === 'TEXT'" link type="primary" @click="showPage">查看原页</el-button>
          </div>
          <template v-if="section.type === 'TABLE'">
            <el-form inline @submit.prevent="searchRows" class="document-query">
              <el-form-item label="表内查询"><el-input v-model="keyword" placeholder="输入表格内容" clearable @clear="searchRows" /></el-form-item>
              <el-form-item><el-button type="primary" icon="Search" @click="searchRows">查询</el-button></el-form-item>
            </el-form>
            <div class="source-table-scroll">
              <table class="source-document-table">
                <thead v-if="section.headers?.length"><tr v-for="(row, ri) in section.headers" :key="ri"><template v-for="(_,ci) in columnCount" :key="ci"><th v-if="cell(ri,ci,row).show" :rowspan="cell(ri,ci,row).rowspan" :colspan="cell(ri,ci,row).colspan">{{ cell(ri,ci,row).value }}</th></template></tr></thead>
                <tbody><tr v-for="row in section.data" :key="row.index"><template v-for="(_,ci) in columnCount" :key="ci"><td v-if="cell(row.index,ci,row.cells).show" :rowspan="cell(row.index,ci,row.cells).rowspan" :colspan="cell(row.index,ci,row.cells).colspan" :title="sourceHint(row,ci)">{{ cell(row.index,ci,row.cells).value }}</td></template></tr></tbody>
              </table>
              <el-empty v-if="!section.data?.length && !section.headers?.length" description="本工作表没有单元格数据，可从左侧查看插图" />
            </div>
            <div v-for="note in section.notes || []" :key="note" class="document-note">{{ note }}</div>
            <pagination v-if="section.total > 0" v-model:page="pageNum" v-model:limit="pageSize" :total="Number(section.total)" :page-sizes="[20,50,100,200]" @pagination="loadSection" />
          </template>
          <template v-else-if="section.type === 'TEXT'">
            <pre class="document-text">{{ section.text }}</pre>
            <el-pagination v-if="section.total > section.pageSize" v-model:current-page="pageNum" :page-size="section.pageSize" :total="section.total" layout="prev,pager,next" @current-change="loadSection" />
          </template>
          <template v-else>
            <div v-loading="assetLoading" class="document-figure">
              <iframe v-if="assetUrl && assetType === 'application/pdf'" :src="assetUrl" title="原文档图表预览" />
              <el-image v-else-if="assetUrl" :src="assetUrl" :preview-src-list="[assetUrl]" fit="contain" :alt="section.title" />
            </div>
          </template>
        </template>
        <el-empty v-else-if="!loading" description="暂无文档内容" />
      </main>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-for="(warning,index) in manifest.warnings || []" :key="index" class="document-note">{{ warning.page ? `第${warning.page}页：` : '' }}{{ warning.message }}</div>
    <template #footer><el-button @click="visible=false">关 闭</el-button></template>
  </el-dialog>
  <el-dialog v-model="pageVisible" title="原文页面" width="min(1100px, calc(100vw - 24px))" append-to-body @closed="clearPage"><div v-loading="pageLoading"><el-image v-if="pageUrl" :src="pageUrl" fit="contain" class="document-original-page" /></div></el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const props=defineProps({fieldId:Number}),emit=defineEmits(['parsed'])
const visible=ref(false),source=ref(),manifest=ref({}),section=ref(),parseId=ref(),sectionId=ref(),loading=ref(false),sectionLoading=ref(false),parsing=ref(false),error=ref('')
const directoryQuery=ref(''),keyword=ref(''),pageNum=ref(1),pageSize=ref(50),assetUrl=ref(''),assetType=ref(''),assetLoading=ref(false),pageVisible=ref(false),pageUrl=ref(''),pageLoading=ref(false)
const readOnly=ref(false)
let generation=0,sectionGeneration=0
const base=computed(()=>source.value?.reference_id?`/api/drilling/reference-materials/${source.value.reference_id}`:`/api/drilling/fields/${Number(source.value?.source_field_id||source.value?.field_id)||props.fieldId}/registry/sources/${source.value?.source_id}`)
const directory=computed(()=>['TABLE','TEXT','FIGURE'].map(type=>({id:type,label:{TABLE:'表格与工作表',TEXT:'正文',FIGURE:'插图与图表'}[type],children:(manifest.value.sections||[]).filter(s=>(type==='FIGURE'?['FIGURE','CHART'].includes(s.content_type):s.content_type===type)&&(!directoryQuery.value||s.title.toLowerCase().includes(directoryQuery.value.toLowerCase()))).map(s=>({id:Number(s.section_id),label:s.title,section:s}))})).filter(g=>g.children.length))
const columnCount=computed(()=>Number(section.value?.column_count)||Math.max(0,...(section.value?.headers||[]).map(r=>r.length),...(section.value?.data||[]).map(r=>r.cells.length)))
const location=computed(()=>[section.value?.sheet?`工作表：${section.value.sheet}`:'',section.value?.pages?.length?`原文第${section.value.pages.join('、')}页`:section.value?.page?`原文第${section.value.page}页`:''].filter(Boolean).join(' · '))
const visibleRows=computed(()=>[...(section.value?.headers||[]).map((_,i)=>i),...(section.value?.data||[]).map(r=>r.index)])
function cell(ri,ci,row){
  const merges=keyword.value?[]:section.value?.merges||[]
  const merged=merges.find(m=>ri>=m.row&&ri<m.row+m.rowspan&&ci>=m.column&&ci<m.column+m.colspan)
  if(!merged)return {show:true,rowspan:1,colspan:1,value:row?.[ci]??''}
  const present=visibleRows.value.filter(i=>i>=merged.row&&i<merged.row+merged.rowspan)
  return {show:ri===present[0]&&ci===merged.column,rowspan:present.length,colspan:merged.colspan,value:merged.value??row?.[ci]??''}
}
function sourceHint(row,ci){const hint=row.source?`原文第${row.source.page}页，第${row.source.line}行`:`来源行号：${row.index+1}`;const formula=section.value?.formulas?.[`${row.index}:${ci}`];return formula?`${hint}\n公式：${formula}`:hint}
function clearAsset(){if(assetUrl.value)URL.revokeObjectURL(assetUrl.value);assetUrl.value='';assetType.value=''}
function clearPage(){if(pageUrl.value)URL.revokeObjectURL(pageUrl.value);pageUrl.value=''}
function close(){generation++;sectionGeneration++;clearAsset();clearPage();section.value=undefined}
async function fetchManifest(id){return request({url:base.value+'/content',params:id?{parseId:id}:{}})}
async function show(row,options={}){close();readOnly.value=!!options.readOnly;source.value=row;manifest.value={};parseId.value=undefined;sectionId.value=undefined;directoryQuery.value='';keyword.value='';visible.value=true;await loadManifest()}
async function showReference(row){await show({...row,reference_id:row.reference_id})}
async function loadManifest(){const ticket=++generation;loading.value=true;error.value='';try{const result=await fetchManifest(parseId.value);if(ticket!==generation)return;manifest.value=result;parseId.value=Number(result.parse_id);const first=result.sections?.find(s=>Number(s.section_id)===sectionId.value)||result.sections?.find(s=>s.content_type==='TABLE')||result.sections?.[0];if(first)await selectSection({section:first})}catch(e){if(ticket===generation)error.value=e.message||'文档内容读取失败'}finally{if(ticket===generation)loading.value=false}}
async function selectSection(node){if(!node.section)return;sectionId.value=Number(node.section.section_id);pageNum.value=1;keyword.value='';await loadSection()}
async function searchRows(){pageNum.value=1;await loadSection()}
async function loadSection(){if(!sectionId.value)return;const ticket=++sectionGeneration;sectionLoading.value=true;error.value='';clearAsset();try{const result=await request({url:`${base.value}/content/${sectionId.value}`,params:{parseId:parseId.value,pageNum:pageNum.value,pageSize:pageSize.value,keyword:keyword.value}});if(ticket!==sectionGeneration)return;section.value=result;if(['FIGURE','CHART'].includes(result.type))await loadAsset(ticket)}catch(e){if(ticket===sectionGeneration)error.value=e.message||'章节读取失败'}finally{if(ticket===sectionGeneration)sectionLoading.value=false}}
async function loadAsset(ticket){assetLoading.value=true;try{const blob=await request({url:`${base.value}/content/${sectionId.value}/asset`,params:{parseId:parseId.value},responseType:'blob',timeout:240000});if(ticket!==sectionGeneration)return;assetUrl.value=URL.createObjectURL(blob);assetType.value=blob.type}catch(e){if(ticket===sectionGeneration)error.value=e.message||'原图读取失败'}finally{if(ticket===sectionGeneration)assetLoading.value=false}}
async function showPage(){clearPage();pageVisible.value=true;pageLoading.value=true;try{const blob=await request({url:`${base.value}/content/${sectionId.value}/asset`,params:{parseId:parseId.value},responseType:'blob',timeout:240000});if(pageVisible.value)pageUrl.value=URL.createObjectURL(blob)}catch(e){ElMessage.error(e.message||'原页读取失败')}finally{pageLoading.value=false}}
async function reparse(){if(readOnly.value)return;parsing.value=true;error.value='';try{const result=await request({url:base.value+'/reparse',method:'post',data:{},timeout:600000});manifest.value=result;parseId.value=Number(result.parse_id);await loadManifest();emit('parsed',source.value);ElMessage.success('文档内容已保存')}catch(e){error.value=e.message||'文档解析失败'}finally{parsing.value=false}}
defineExpose({show,showReference})
</script>

<style scoped>
.document-toolbar,.document-section-toolbar{display:flex;align-items:center;gap:12px;margin-bottom:14px;min-width:0}.original-name{flex:1;overflow-wrap:anywhere}.document-workspace{display:grid;grid-template-columns:250px minmax(0,1fr);gap:16px;min-height:580px}.document-directory{border-right:1px solid var(--el-border-color-light);padding-right:12px;min-width:0}.document-directory .el-input{margin-bottom:12px}.document-tree-label{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.document-body{min-width:0}.document-section-toolbar{flex-wrap:wrap;line-height:1.6}.source-location{font-size:12px;color:var(--el-text-color-secondary)}.source-table-scroll{overflow:auto;max-height:500px;border:1px solid var(--el-border-color-lighter)}.source-document-table{border-collapse:separate;border-spacing:0;min-width:100%;font-size:13px;color:var(--el-text-color-primary);background:var(--el-bg-color)}.source-document-table th,.source-document-table td{border-right:1px solid var(--el-border-color-lighter);border-bottom:1px solid var(--el-border-color-lighter);padding:9px 12px;min-width:100px;max-width:380px;white-space:pre-wrap;overflow-wrap:anywhere;text-align:center;vertical-align:middle}.source-document-table thead{position:sticky;top:0;z-index:1;background:var(--el-fill-color-light)}.source-document-table th{font-weight:600}.document-text{white-space:pre-wrap;overflow-wrap:anywhere;max-height:520px;overflow:auto;padding:12px;border:1px solid var(--el-border-color-lighter);font:13px/1.8 var(--el-font-family);margin:0 0 12px}.document-note{font-size:12px;line-height:1.7;margin-top:8px;color:var(--el-text-color-regular)}.document-figure{min-height:340px}.document-figure iframe{width:100%;height:540px;border:0}.document-figure .el-image{width:100%;height:520px}.document-original-page{width:100%;min-height:300px}.document-query :deep(.el-form-item){margin-bottom:12px}
@media(max-width:760px){.document-workspace{grid-template-columns:1fr;min-height:0}.document-directory{border-right:0;border-bottom:1px solid var(--el-border-color-light);padding:0 0 10px}.document-directory :deep(.el-scrollbar){height:160px!important}.document-toolbar{flex-wrap:wrap}.original-name{flex-basis:100%}.source-table-scroll{max-height:400px}}
</style>
