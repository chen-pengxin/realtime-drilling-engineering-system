<template>
  <CenterList ref="list" title="任务历史" module="history" :types="taskTypes" :statuses="options(['QUEUED','RUNNING','SUCCESS','FEASIBLE_NOT_CONVERGED','TIMEOUT','SUCCEEDED','FAILED','STOPPED','LOST','MODEL_NOT_CONFIGURED','INFEASIBLE','BEST_EFFORT'])" extension="json">
    <el-table-column prop="title" label="任务" min-width="180" />
    <el-table-column prop="record_type" label="类型" min-width="120"><template #default="{row}">{{ label(row.record_type) }}</template></el-table-column>
    <el-table-column prop="well_id" label="井编号" width="100" />
    <el-table-column prop="status" label="状态" min-width="130" sortable="custom"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ label(row.status) }}</el-tag></template></el-table-column>
    <el-table-column prop="duration_seconds" label="耗时（s）" width="120" sortable="custom"><template #default="{row}">{{ row.duration_seconds==null?'—':Number(row.duration_seconds).toFixed(3) }}</template></el-table-column>
    <el-table-column prop="config_version" label="配置版本" width="100" />
    <el-table-column prop="operator" label="操作人" min-width="110" />
    <el-table-column prop="finished_at" label="完成时间" min-width="174" sortable="custom"><template #default="{row}">{{ formatTime(row.finished_at) }}</template></el-table-column>
    <template #actions="{row,fieldId}"><el-button link type="primary" @click="show(row,fieldId)">输入与结果</el-button></template>
  </CenterList>
  <el-dialog v-model="visible" title="任务详情" width="1000px" class="drilling-dialog"><div v-loading="loading"><RecordDetails v-if="detail" :value="detail" /></div><template #footer><el-button @click="visible=false">关闭</el-button></template></el-dialog>
</template>
<script setup>
import {ref} from 'vue'
import {centerGet} from '@/api/drillingCenters'
import CenterList from '../shared/CenterList.vue'
import RecordDetails from '../shared/RecordDetails.vue'
import {label,options,statusType,taskTypes,formatTime} from '../shared/labels'
const list=ref(),detail=ref(null),visible=ref(false),loading=ref(false)
async function show(row,fieldId){visible.value=true;detail.value=null;loading.value=true;try{detail.value=await centerGet('history',fieldId,row.record_key)}finally{loading.value=false}}
</script>
