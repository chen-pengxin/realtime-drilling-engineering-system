<template>
  <CenterList ref="list" title="数据质量中心" module="quality" :types="options(['INVALID','OUT_OF_ORDER','DUPLICATE','INVALID_STATION','DUPLICATE_MD','VERSION_CONFLICT'])" :statuses="options(['ISOLATED','RESOLVED','IGNORED'])" :action-width="160">
    <template #toolbar="{selected,fieldId,reload}">
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:quality:check']" type="primary" plain icon="Search" :disabled="!fieldId" :loading="checking" @click="check(fieldId,reload)">执行检查</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:quality:resolve']" type="success" plain icon="Check" :disabled="!selected.length" @click="open(selected,fieldId)">批量处理</el-button></el-col>
    </template>
    <el-table-column prop="record_type" label="问题类型" min-width="150"><template #default="{row}">{{ label(row.record_type) }}</template></el-table-column>
    <el-table-column prop="well_name" label="井名" min-width="120" />
    <el-table-column prop="survey_version_id" label="测斜版本" width="110" />
    <el-table-column prop="md" label="MD（m）" width="110" sortable="custom" />
    <el-table-column prop="status" label="状态" width="110" sortable="custom"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ row.status==='RESOLVED'?'已处理':label(row.status) }}</el-tag></template></el-table-column>
    <el-table-column prop="resolution_note" label="说明" min-width="240" show-overflow-tooltip />
    <template #actions="{row,fieldId}"><el-button link type="primary" @click="detail=row">详情</el-button><el-button v-hasPermi="['drilling:quality:resolve']" link type="primary" @click="open([row],fieldId)">处理</el-button></template>
  </CenterList>
  <el-dialog v-model="visible" title="处理质量问题" width="560px" class="drilling-dialog"><el-form :model="form" label-width="90px"><el-form-item label="处理结果"><el-select v-model="form.status"><el-option label="已处理" value="RESOLVED"/><el-option label="已忽略" value="IGNORED"/></el-select></el-form-item><el-form-item label="处理说明" required><el-input v-model="form.note" type="textarea" :rows="4" maxlength="500" show-word-limit /></el-form-item></el-form><template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></template></el-dialog>
  <el-dialog :model-value="!!detail" title="质量问题详情" width="800px" class="drilling-dialog" @close="detail=null"><RecordDetails v-if="detail" :value="detail" /></el-dialog>
</template>
<script setup>
import {ref} from 'vue'
import {ElMessage} from 'element-plus'
import {centerAction} from '@/api/drillingCenters'
import CenterList from '../shared/CenterList.vue'
import RecordDetails from '../shared/RecordDetails.vue'
import {label,options,statusType} from '../shared/labels'
const list=ref(),detail=ref(null),visible=ref(false),saving=ref(false),checking=ref(false),form=ref({ids:[],status:'RESOLVED',note:''}),fieldId=ref()
function open(rows,id){fieldId.value=id;form.value={ids:rows.map(x=>x.record_key),status:'RESOLVED',note:''};visible.value=true}
async function check(id,reload){checking.value=true;try{await centerAction('quality',id,'check');ElMessage.success('检查完成');await reload()}finally{checking.value=false}}
async function save(){if(!form.value.note.trim()){ElMessage.warning('请填写处理说明');return}saving.value=true;try{await centerAction('quality',fieldId.value,'resolve',form.value);visible.value=false;ElMessage.success('处理记录已保存');await list.value.load()}finally{saving.value=false}}
</script>
