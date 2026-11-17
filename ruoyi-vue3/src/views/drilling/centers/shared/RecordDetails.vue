<template>
  <div class="record-details">
    <template v-if="Array.isArray(parsed)">
      <el-table v-if="parsed.length" :data="pageRows" max-height="420" @sort-change="sort=$event">
        <el-table-column v-for="key in columns" :key="key" :prop="key" :label="fieldNames[key]||key" min-width="140" sortable show-overflow-tooltip><template #default="{row}">{{ cell(row[key]) }}</template></el-table-column>
      </el-table>
      <el-empty v-else description="暂无记录" :image-size="48" />
      <pagination v-if="parsed.length>10" small :total="parsed.length" v-model:page="page" v-model:limit="size" :page-sizes="[10,20,50]" />
    </template>
    <template v-else-if="parsed && typeof parsed==='object'">
      <el-descriptions v-if="scalars.length" :column="1" border><el-descriptions-item v-for="[key,v] in scalars" :key="key" :label="fieldNames[key]||key">{{ cell(v) }}</el-descriptions-item></el-descriptions>
      <el-collapse v-if="nested.length"><el-collapse-item v-for="[key,v] in nested" :key="key" :name="key" :title="fieldNames[key]||key"><RecordDetails :value="v" /></el-collapse-item></el-collapse>
    </template>
    <span v-else>{{ cell(parsed) }}</span>
  </div>
</template>
<script setup>
import {computed,ref,watch} from 'vue'
import {fieldNames,label} from './labels'
const props=defineProps({value:{default:null}})
const parse=x=>{if(typeof x==='string'&&/^[{[]/.test(x)){try{return JSON.parse(x)}catch{}}return x}
const parsed=computed(()=>parse(props.value)),page=ref(1),size=ref(10),sort=ref({})
const entries=computed(()=>Object.entries(parsed.value||{}).map(([k,v])=>[k,parse(v)]))
const scalars=computed(()=>entries.value.filter(([,v])=>v==null||typeof v!=='object'))
const nested=computed(()=>entries.value.filter(([,v])=>v!=null&&typeof v==='object'))
const columns=computed(()=>[...new Set(parsed.value.flatMap(x=>x&&typeof x==='object'?Object.keys(x):['value']))])
const pageRows=computed(()=>{let rows=(parsed.value||[]).map(x=>x&&typeof x==='object'?x:{value:x});if(sort.value.order){const k=sort.value.prop,d=sort.value.order==='ascending'?1:-1;rows=[...rows].sort((a,b)=>String(a[k]??'').localeCompare(String(b[k]??''),'zh-CN',{numeric:true})*d)}return rows.slice((page.value-1)*size.value,page.value*size.value)})
function cell(x){return x&&typeof x==='object'?JSON.stringify(x):label(x)}
watch(()=>props.value,()=>page.value=1)
</script>
<style scoped>.record-details{min-width:0;overflow-wrap:anywhere}.record-details :deep(.el-descriptions__label){width:170px}.record-details :deep(.pagination-container){margin-bottom:20px}.record-details :deep(.el-collapse){margin-top:12px}.record-details :deep(.el-descriptions__content){white-space:pre-wrap}</style>
