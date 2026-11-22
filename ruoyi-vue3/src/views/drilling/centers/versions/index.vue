<template>
  <CenterList ref="list" title="版本与变更记录" module="versions" :types="options(['CONFIG','SURVEY','MODEL','PLAN'])" :statuses="options(['CURRENT','HISTORY','READY','ARCHIVED','SUCCESS','FEASIBLE_NOT_CONVERGED','TIMEOUT','BEST_EFFORT','INFEASIBLE'])" extension="json">
    <template #toolbar="{selected,fieldId}"><el-col :span="1.5"><el-button type="primary" plain icon="Switch" :disabled="selected.length!==2||loading" @click="compare(selected,fieldId)">版本比较</el-button></el-col><el-col :span="1.5"><el-button icon="Document" :disabled="!fieldId" @click="changes(fieldId)">变更记录</el-button></el-col></template>
    <el-table-column prop="title" label="对象名称" min-width="200" />
    <el-table-column prop="record_type" label="类型" width="120"><template #default="{row}">{{ label(row.record_type) }}</template></el-table-column>
    <el-table-column prop="version_no" label="版本 / 方案编号" min-width="140" sortable="custom" />
    <el-table-column prop="status" label="状态" min-width="120" sortable="custom"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ label(row.status) }}</el-tag></template></el-table-column>
    <el-table-column prop="operator" label="操作人" min-width="110" />
    <template #actions="{row,fieldId}"><el-button link type="primary" @click="show(row,fieldId)">详情与关联任务</el-button></template>
  </CenterList>
  <ResizableDialog v-model="visible" :title="dialogTitle" width="1000px" class="drilling-dialog"><div v-loading="loading"><template v-if="comparison"><el-table :data="differenceRows" @sort-change="differenceSort=$event"><el-table-column prop="field" label="字段" min-width="220" sortable="custom" /><el-table-column label="版本一" min-width="220"><template #default="{row}">{{ valueText(row.before) }}</template></el-table-column><el-table-column label="版本二" min-width="220"><template #default="{row}">{{ valueText(row.after) }}</template></el-table-column></el-table><pagination v-if="comparison.differences.length" :total="comparison.differences.length" v-model:page="differencePage" v-model:limit="differenceSize" /></template><RecordDetails v-else-if="detail" :value="detail" /></div><template #footer><el-button @click="visible=false">关闭</el-button></template></ResizableDialog>
  <ResizableDialog v-model="changesVisible" title="工程变更记录" width="1100px" class="drilling-dialog"><el-form inline @submit.prevent="loadChanges"><el-form-item label="关键词"><el-input v-model="changeQuery.keyword" clearable @keyup.enter="changeQuery.pageNum=1;loadChanges()" /></el-form-item><el-button type="primary" icon="Search" @click="changeQuery.pageNum=1;loadChanges()">搜索</el-button></el-form><el-table :data="changeRows" v-loading="changesLoading" @sort-change="changeSort"><el-table-column prop="record_id" label="编号" width="90" sortable="custom" /><el-table-column prop="title" label="操作" width="150" /><el-table-column prop="record_type" label="资源" width="150" /><el-table-column prop="resource_id" label="资源编号" width="120" /><el-table-column prop="detail_json" label="变更内容" min-width="260" show-overflow-tooltip /><el-table-column prop="created_at" label="时间" width="180" sortable="custom"><template #default="{row}">{{ formatTime(row.created_at) }}</template></el-table-column></el-table><pagination v-if="changeTotal" :total="changeTotal" v-model:page="changeQuery.pageNum" v-model:limit="changeQuery.pageSize" @pagination="loadChanges" /></ResizableDialog>
</template>
<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import {computed,ref} from 'vue'
import {centerGet,centerAction,centerList} from '@/api/drillingCenters'
import CenterList from '../shared/CenterList.vue'
import RecordDetails from '../shared/RecordDetails.vue'
import {label,options,statusType,formatTime} from '../shared/labels'
const list=ref(),visible=ref(false),loading=ref(false),detail=ref(null),comparison=ref(null),dialogTitle=ref('版本详情'),changesVisible=ref(false),changesLoading=ref(false),changeRows=ref([]),changeTotal=ref(0),changeFieldId=ref(),changeQuery=ref({pageNum:1,pageSize:20,keyword:'',sort:'created_at',order:'desc'})
const differencePage=ref(1),differenceSize=ref(20),differenceSort=ref({})
const differenceRows=computed(()=>{let rows=comparison.value?.differences||[];if(differenceSort.value.order){const direction=differenceSort.value.order==='ascending'?1:-1;rows=[...rows].sort((a,b)=>a.field.localeCompare(b.field,'zh-CN',{numeric:true})*direction)}return rows.slice((differencePage.value-1)*differenceSize.value,differencePage.value*differenceSize.value)})
const valueText=x=>x==null?'—':typeof x==='object'?JSON.stringify(x):x
async function show(row,fieldId){visible.value=true;dialogTitle.value='版本详情';loading.value=true;detail.value=null;comparison.value=null;try{detail.value=await centerGet('versions',fieldId,row.record_key)}finally{loading.value=false}}
async function compare(rows,fieldId){differencePage.value=1;differenceSort.value={};visible.value=true;dialogTitle.value=`版本比较：${rows[0].version_no} / ${rows[1].version_no}`;loading.value=true;detail.value=null;comparison.value=null;try{comparison.value=await centerAction('versions',fieldId,'compare',{ids:rows.map(x=>x.record_key)})}finally{loading.value=false}}
function changes(fieldId){changeFieldId.value=fieldId;changeQuery.value.pageNum=1;changesVisible.value=true;loadChanges()}
async function loadChanges(){changesLoading.value=true;try{const r=await centerList('versions/changes',changeFieldId.value,changeQuery.value);changeRows.value=r.rows;changeTotal.value=Number(r.total)}finally{changesLoading.value=false}}
function changeSort({prop,order}){changeQuery.value.sort=order?prop:'created_at';changeQuery.value.order=order==='ascending'?'asc':'desc';changeQuery.value.pageNum=1;loadChanges()}
</script>
