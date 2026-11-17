<template>
  <DrillingPageFrame :title="title" basic-context>
    <el-form v-show="showSearch" ref="formRef" :model="query" inline class="center-search" @submit.prevent="search">
      <el-form-item label="关键词" prop="keyword"><el-input v-model="query.keyword" clearable placeholder="请输入查询内容" @keyup.enter="search" /></el-form-item>
      <el-form-item v-if="types.length" label="类型" prop="type"><el-select v-model="query.type" clearable placeholder="全部"><el-option v-for="x in types" :key="x.value" :label="x.label" :value="x.value" /></el-select></el-form-item>
      <el-form-item v-if="statuses.length" label="状态" prop="status"><el-select v-model="query.status" clearable placeholder="全部"><el-option v-for="x in statuses" :key="x.value" :label="x.label" :value="x.value" /></el-select></el-form-item>
      <el-form-item v-if="severities.length" label="等级" prop="severity"><el-select v-model="query.severity" clearable placeholder="全部"><el-option v-for="x in severities" :key="x.value" :label="x.label" :value="x.value" /></el-select></el-form-item>
      <el-form-item v-if="showWell" label="井" prop="wellId"><el-select v-model="query.wellId" clearable filterable placeholder="全部"><el-option v-for="w in wells" :key="w.well_id" :label="w.well_name" :value="w.well_id" /></el-select></el-form-item>
      <el-form-item label="时间"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" range-separator="至" /></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="search">搜索</el-button><el-button icon="Refresh" @click="reset">重置</el-button></el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8 center-toolbar">
      <slot name="toolbar" :selected="selected" :field-id="fieldId" :reload="load" :busy="loading" />
      <el-col :span="1.5" v-if="exportAction"><el-button v-hasPermi="[`drilling:${module}:${exportAction==='download'?'download':'export'}`]" type="warning" plain icon="Download" :disabled="!selected.length || loading" :loading="exporting" @click="exportRows">{{ exportAction==='download'?'批量下载':'导出所选' }}</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="load" />
    </el-row>
    <el-table ref="tableRef" :data="rows" v-loading="loading" row-key="record_key" @selection-change="selected=$event" @sort-change="sort">
      <el-table-column type="selection" width="48" />
      <el-table-column prop="record_id" label="编号" width="90" sortable="custom" />
      <slot :rows="rows" :field-id="fieldId" />
      <el-table-column prop="created_at" label="时间" min-width="174" sortable="custom"><template #default="{row}">{{ formatTime(row.created_at) }}</template></el-table-column>
      <el-table-column label="操作" :width="actionWidth" :fixed="mobile?false:'right'"><template #default="{row}"><slot name="actions" :row="row" :field-id="fieldId" :reload="load" /></template></el-table-column>
      <template #empty><el-empty :description="error || '暂无记录'" :image-size="64" /></template>
    </el-table>
    <pagination v-show="total>0" :total="total" :page-sizes="[10,20,30,50,100]" :layout="mobile?'total, sizes, prev, next':'total, sizes, prev, pager, next, jumper'" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load" />
  </DrillingPageFrame>
</template>
<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useWindowSize } from '@vueuse/core'
import { centerBlob, centerList, saveBlob } from '@/api/drillingCenters'
import DrillingPageFrame from '../../layout/DrillingPageFrame.vue'
import { useDrillingContext } from '../../composables/useDrillingContext'
import { formatTime } from './labels'
const props=defineProps({title:String,module:String,types:{type:Array,default:()=>[]},statuses:{type:Array,default:()=>[]},severities:{type:Array,default:()=>[]},showWell:{type:Boolean,default:true},exportAction:{type:String,default:'export'},extension:{type:String,default:'csv'},actionWidth:{type:Number,default:180}})
const {fieldId,wells}=useDrillingContext()
const {width}=useWindowSize(),mobile=computed(()=>width.value<=650)
const query=ref({pageNum:1,pageSize:20,keyword:'',type:'',status:'',severity:'',wellId:'',sort:'created_at',order:'desc'})
const formRef=ref(),tableRef=ref(),rows=ref([]),selected=ref([]),total=ref(0),loading=ref(false),exporting=ref(false),showSearch=ref(true),dates=ref([]),error=ref('')
let revision=0
async function load(){const rev=++revision;selected.value=[];error.value='';if(!fieldId.value){rows.value=[];total.value=0;loading.value=false;return}loading.value=true;try{const r=await centerList(props.module,fieldId.value,{...query.value,beginTime:dates.value?.[0],endTime:dates.value?.[1]});if(rev!==revision)return;rows.value=r.rows;total.value=Number(r.total);if(!rows.value.length&&total.value&&query.value.pageNum>1){query.value.pageNum=Math.max(1,Math.ceil(total.value/query.value.pageSize));await load()}}catch(e){if(rev===revision){rows.value=[];total.value=0;error.value='加载失败，请刷新重试'}}finally{if(rev===revision)loading.value=false}}
function search(){query.value.pageNum=1;load()}
async function reset(){formRef.value?.resetFields();dates.value=[];query.value.sort='created_at';query.value.order='desc';tableRef.value?.clearSort();await nextTick();search()}
function sort({prop,order}){query.value.sort=order?prop:'created_at';query.value.order=order==='ascending'?'asc':'desc';search()}
async function exportRows(){exporting.value=true;try{const blob=await centerBlob(props.module,fieldId.value,props.exportAction,{ids:selected.value.map(x=>x.record_key)});saveBlob(blob,`${props.module}.${props.extension}`)}finally{exporting.value=false}}
watch(fieldId,()=>{query.value.pageNum=1;query.value.wellId='';load()},{immediate:true})
defineExpose({load,selected,fieldId,rows,query})
</script>
<style scoped>
.center-search :deep(.el-input),.center-search :deep(.el-select){width:180px}.center-search :deep(.el-date-editor){width:300px}.center-toolbar{row-gap:8px}.center-toolbar :deep(.el-col){max-width:100%}
@media(max-width:650px){.center-search :deep(.el-form-item){display:flex;margin-right:0}.center-search :deep(.el-form-item__content){min-width:0}.center-search :deep(.el-input),.center-search :deep(.el-select),.center-search :deep(.el-date-editor){width:100%}.center-toolbar :deep(.top-right-btn){margin-left:auto}}
</style>
