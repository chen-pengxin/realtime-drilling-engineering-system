<template>
  <CenterList ref="list" title="报告中心" module="reports" :types="options(['ENGINEERING','COLLISION','REPLANNING','SURVEY','PREDICTION','TRAINING'])" :statuses="options(['READY','ARCHIVED'])" :show-well="false" export-action="download" extension="zip">
    <template #toolbar="{selected,fieldId,reload}"><el-col :span="1.5"><el-button v-hasPermi="['drilling:reports:create']" type="primary" plain icon="Plus" :disabled="!fieldId" @click="open(fieldId)">生成报告</el-button></el-col><el-col :span="1.5"><el-button v-hasPermi="['drilling:reports:archive']" type="danger" plain icon="FolderDelete" :disabled="!selected.length||busy" @click="archive(selected,fieldId,reload)">批量归档</el-button></el-col></template>
    <el-table-column prop="title" label="报告名称" min-width="220" />
    <el-table-column prop="record_type" label="类型" width="130"><template #default="{row}">{{ label(row.record_type) }}</template></el-table-column>
    <el-table-column prop="source_key" label="来源记录" min-width="150" />
    <el-table-column prop="format" label="格式" width="90"><template #default="{row}">{{ row.format.toUpperCase() }}</template></el-table-column>
    <el-table-column prop="file_size" label="大小" width="110" sortable="custom"><template #default="{row}">{{ (Number(row.file_size)/1024).toFixed(1) }} KB</template></el-table-column>
    <el-table-column prop="status" label="状态" width="100" sortable="custom"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ label(row.status) }}</el-tag></template></el-table-column>
    <el-table-column prop="operator" label="生成人" min-width="100" />
    <template #actions="{row,fieldId,reload}"><el-button v-if="row.status==='READY'" v-hasPermi="['drilling:reports:download']" link type="primary" :disabled="busy" @click="download(row,fieldId)">下载</el-button><el-button v-if="row.status==='READY'" v-hasPermi="['drilling:reports:archive']" link type="danger" :disabled="busy" @click="archive([row],fieldId,reload)">归档</el-button><el-button link type="primary" @click="show(row,fieldId)">详情</el-button></template>
  </CenterList>
  <el-dialog v-model="visible" title="生成报告" width="600px" class="drilling-dialog" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="报告名称" prop="title"><el-input v-model="form.title" maxlength="128" /></el-form-item>
      <el-form-item label="报告类型" prop="type"><el-select v-model="form.type" @change="form.sourceKey='';sourceRows=[];searchSources('')"><el-option v-for="x in options(['ENGINEERING','COLLISION','REPLANNING','SURVEY','PREDICTION','TRAINING'])" :key="x.value" :label="label(x.value)" :value="x.value" /></el-select></el-form-item>
      <el-form-item v-if="form.type!=='ENGINEERING'" label="来源记录" prop="sourceKey"><el-select v-model="form.sourceKey" filterable remote reserve-keyword :remote-method="searchSources" :loading="sourceLoading" placeholder="输入任务名称或井名查询"><el-option v-for="x in sourceRows" :key="x.record_key" :value="x.record_key" :label="`${x.title} / ${x.record_key} / ${label(x.status)}`" /></el-select></el-form-item>
      <el-form-item label="报告格式" prop="format"><el-select v-model="form.format"><el-option v-for="x in ['pdf','docx','xlsx','bundle']" :key="x" :value="x" :label="x==='bundle'?'Word / PDF / Excel 打包':x.toUpperCase()" /></el-select></el-form-item>
    </el-form><template #footer><el-button :disabled="busy" @click="visible=false">取消</el-button><el-button type="primary" :loading="busy" @click="create">生成</el-button></template>
  </el-dialog>
  <el-dialog :model-value="!!detail" title="报告详情" width="760px" class="drilling-dialog" @close="detail=null"><RecordDetails v-if="detail" :value="detail" /></el-dialog>
</template>
<script setup>
import {ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {centerAction,centerGet,reportDownload,reportSources,saveBlob} from '@/api/drillingCenters'
import CenterList from '../shared/CenterList.vue'
import RecordDetails from '../shared/RecordDetails.vue'
import {label,options,statusType} from '../shared/labels'
const list=ref(),formRef=ref(),visible=ref(false),busy=ref(false),detail=ref(null),fieldId=ref(),sourceRows=ref([]),sourceLoading=ref(false),form=ref({title:'',type:'ENGINEERING',format:'pdf',sourceKey:''})
const required={required:true,message:'请填写或选择此项',trigger:'change'},rules={title:[required],type:[required],format:[required],sourceKey:[required]}
function open(id){fieldId.value=id;form.value={title:'工程报告',type:'ENGINEERING',format:'pdf',sourceKey:''};sourceRows.value=[];visible.value=true}
let sourceRevision=0
async function searchSources(keyword){if(form.value.type==='ENGINEERING')return;const revision=++sourceRevision;sourceLoading.value=true;try{const r=await reportSources(fieldId.value,{type:form.value.type,keyword,pageNum:1,pageSize:100});if(revision===sourceRevision)sourceRows.value=r.rows}finally{if(revision===sourceRevision)sourceLoading.value=false}}
async function create(){if(!await formRef.value.validate().catch(()=>false))return;busy.value=true;try{await centerAction('reports',fieldId.value,'',form.value);visible.value=false;ElMessage.success('报告已生成');await list.value.load()}finally{busy.value=false}}
async function show(row,id){detail.value=await centerGet('reports',id,row.record_key)}
async function download(row,id){busy.value=true;try{const blob=await reportDownload(id,row.record_id);saveBlob(blob,`${row.title}.${row.format==='bundle'?'zip':row.format}`)}finally{busy.value=false}}
async function archive(rows,id,reload){try{await ElMessageBox.confirm(`确认归档所选 ${rows.length} 份报告？`,'归档确认',{type:'warning'});busy.value=true;await centerAction('reports',id,'archive',{ids:rows.map(x=>x.record_key)});ElMessage.success('报告已归档');await reload()}catch(e){if(!['cancel','close'].includes(e))console.error(e)}finally{busy.value=false}}
</script>
