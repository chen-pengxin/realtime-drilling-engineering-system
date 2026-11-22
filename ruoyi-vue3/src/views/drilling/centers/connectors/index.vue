<template>
  <CenterList ref="list" title="连接器管理" module="connectors" :types="options(kinds)" :statuses="options(['DISABLED','ENABLED','CONNECTED','RECONNECTING','ERROR'])" :action-width="210">
    <template #toolbar="{selected,fieldId,reload}">
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:connectors:save']" type="primary" plain icon="Plus" :disabled="!fieldId" @click="open(null,fieldId)">新增</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:connectors:save']" type="success" plain icon="VideoPlay" :disabled="!selected.length||busy" @click="state('enable',selected,fieldId,reload)">批量启用</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:connectors:save']" type="danger" plain icon="VideoPause" :disabled="!selected.length||busy" @click="state('disable',selected,fieldId,reload)">批量停用</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:connectors:run']" plain icon="Refresh" :disabled="!selected.length||busy" :loading="busy" @click="poll(selected,fieldId,reload)">立即读取</el-button></el-col>
    </template>
    <el-table-column prop="title" label="连接器名称" min-width="170" />
    <el-table-column prop="record_type" label="类型" min-width="160"><template #default="{row}">{{ label(row.record_type) }}</template></el-table-column>
    <el-table-column prop="well_name" label="井名" min-width="120" />
    <el-table-column prop="status" label="状态" min-width="120" sortable="custom"><template #default="{row}"><el-tag :type="statusType(row.status)">{{ label(row.status) }}</el-tag></template></el-table-column>
    <el-table-column prop="last_heartbeat" label="最后心跳" min-width="174" sortable="custom"><template #default="{row}">{{ formatTime(row.last_heartbeat) }}</template></el-table-column>
    <el-table-column prop="last_error" label="最近错误" min-width="220" show-overflow-tooltip />
    <template #actions="{row,fieldId,reload}"><el-button link type="primary" @click="show(row,fieldId)">详情</el-button><el-button v-hasPermi="['drilling:connectors:save']" link type="primary" :disabled="row.status!=='DISABLED'" @click="open(row,fieldId)">修改</el-button><el-button v-hasPermi="['drilling:connectors:save']" link :type="row.status==='DISABLED'?'success':'danger'" :disabled="busy" @click="state(row.status==='DISABLED'?'enable':'disable',[row],fieldId,reload)">{{ row.status==='DISABLED'?'启用':'停用' }}</el-button></template>
  </CenterList>
  <ResizableDialog v-model="visible" :title="form.id?'修改连接器':'新增连接器'" width="760px" class="drilling-dialog" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="140px" v-loading="formLoading">
      <el-form-item label="连接器名称" prop="name"><el-input v-model="form.name" maxlength="128" /></el-form-item>
      <el-form-item label="类型" prop="type"><el-select v-model="form.type"><el-option v-for="x in options(kinds)" :key="x.value" :value="x.value" :label="x.label" /></el-select></el-form-item>
      <el-form-item label="所属井" prop="wellId"><el-select v-model="form.wellId" filterable><el-option v-for="w in wells" :key="w.well_id" :value="w.well_id" :label="w.well_name" /></el-select></el-form-item>
      <el-form-item label="接入井眼" prop="config.wellboreId"><WellboreSelect v-model="form.config.wellboreId" :rows="wellbores" @change="form.wellId=wellbores.find(b=>Number(b.wellbore_id)===Number($event))?.well_id" /></el-form-item>
      <el-form-item v-if="['CSV','EXCEL'].includes(form.type)" label="数据文件" prop="config.fileId"><el-select v-model="form.config.fileId" filterable><el-option v-for="f in files" :key="f.file_id" :label="fileDisplayName(f)" :value="f.file_id" /></el-select></el-form-item>
      <el-form-item v-else-if="form.type!=='SQL_SERVER'" label="连接地址" prop="endpoint"><el-input v-model="form.endpoint" :placeholder="endpointPlaceholder" maxlength="512" /></el-form-item>
      <el-form-item v-if="!['CSV','EXCEL','SERIAL','WITS_TCP'].includes(form.type)" label="凭据环境变量"><el-input v-model="form.credentialRef" :placeholder="form.credentialConfigured?'已配置；留空保留原引用':'env:DRILLING_SOURCE_PASSWORD'" maxlength="104" /><el-checkbox v-if="form.credentialConfigured" v-model="form.clearCredential">清除原引用</el-checkbox></el-form-item>
      <template v-if="['MYSQL','SQL_SERVER'].includes(form.type)">
        <el-form-item v-if="form.type==='MYSQL'" label="数据库账号"><el-input v-model="form.config.username" maxlength="64" /></el-form-item>
        <el-form-item label="数据表" prop="config.table"><el-input v-model="form.config.table" maxlength="64" /></el-form-item>
        <el-form-item label="MD字段"><el-input v-model="form.config.mdColumn" maxlength="64" /></el-form-item><el-form-item label="井斜字段"><el-input v-model="form.config.incColumn" maxlength="64" /></el-form-item><el-form-item label="方位字段"><el-input v-model="form.config.aziColumn" maxlength="64" /></el-form-item>
      </template>
      <el-form-item v-if="form.type==='SERIAL'" label="波特率"><el-input-number v-model="form.config.baudrate" :min="1200" :max="921600" /></el-form-item>
      <el-form-item label="轮询间隔（s）"><el-input-number v-model="form.config.pollSeconds" :min="5" :max="3600" /></el-form-item><el-form-item label="读取超时（s）"><el-input-number v-model="form.config.timeoutSeconds" :min="1" :max="10" /></el-form-item><el-form-item label="每次读取条数"><el-input-number v-model="form.config.batchSize" :min="1" :max="2000" /></el-form-item><el-form-item label="乱序窗口（m）"><el-input-number v-model="form.config.reorderWindow" :min="0" :max="100" :precision="1" /></el-form-item>
    </el-form><template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></template>
  </ResizableDialog>
  <ResizableDialog :model-value="!!detail" title="连接器详情" width="1000px" class="drilling-dialog" @close="detail=null"><RecordDetails v-if="detail" :value="detail" /></ResizableDialog>
</template>
<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import WellboreSelect from '@/views/drilling/layout/WellboreSelect.vue'
import { fileDisplayName } from '@/utils/fileNames'
import {computed,onUnmounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {centerAction,centerGet,connectorFiles} from '@/api/drillingCenters'
import {listWellbores} from '@/api/drillingRegistry'
import {useDrillingContext} from '../../composables/useDrillingContext'
import CenterList from '../shared/CenterList.vue'
import RecordDetails from '../shared/RecordDetails.vue'
import {label,options,statusType,formatTime} from '../shared/labels'
const kinds=['WITS_TCP','SERIAL','WITSML_1_4_1','WITSML_2_0','CSV','EXCEL','MYSQL','SQL_SERVER']
const {wells}=useDrillingContext(),list=ref(),formRef=ref(),visible=ref(false),saving=ref(false),busy=ref(false),formLoading=ref(false),detail=ref(null),files=ref([]),wellbores=ref([]),fieldId=ref()
const defaults=()=>({id:null,name:'',type:'WITS_TCP',wellId:null,endpoint:'',credentialRef:'',clearCredential:false,config:{wellboreId:null,pollSeconds:30,timeoutSeconds:5,batchSize:500,reorderWindow:3,baudrate:9600,table:'',mdColumn:'md',incColumn:'inclination',aziColumn:'azimuth',username:'',fileId:null}})
const form=ref(defaults()),required={required:true,message:'请填写或选择此项',trigger:'change'},rules={name:[required],type:[required],wellId:[required],endpoint:[required],'config.wellboreId':[required],'config.fileId':[required],'config.table':[required]}
const endpointPlaceholder=computed(()=>form.value.type==='MYSQL'?'jdbc:mysql://主机:3306/数据库':form.value.type==='SERIAL'?'/dev/ttyUSB0':form.value.type==='WITS_TCP'?'tcp://主机:端口':'https://主机/trajectory.xml')
async function open(row,id){fieldId.value=id;form.value=defaults();visible.value=true;formLoading.value=true;try{[files.value,wellbores.value]=await Promise.all([connectorFiles(id),listWellbores(id)]);if(row){const r=await centerGet('connectors',id,row.record_key);form.value={...defaults(),id:r.connector_id,name:r.connector_name,type:r.connector_type,wellId:r.well_id,endpoint:r.endpoint_uri||'',credentialConfigured:r.credentialConfigured,credentialRef:'',config:{...defaults().config,...r.config}}}}finally{formLoading.value=false}}
async function save(){if(!await formRef.value.validate().catch(()=>false))return;saving.value=true;try{await centerAction('connectors',fieldId.value,'',form.value);visible.value=false;ElMessage.success('连接器已保存');await list.value.load()}finally{saving.value=false}}
async function state(action,rows,id,reload){try{await ElMessageBox.confirm(`确认${action==='enable'?'启用':'停用'}所选 ${rows.length} 个连接器？`,'操作确认',{type:'warning'});busy.value=true;await centerAction('connectors',id,'state',{action,ids:rows.map(x=>x.record_key)});ElMessage.success('状态已更新');await reload()}catch(e){if(!['cancel','close'].includes(e))console.error(e)}finally{busy.value=false}}
async function poll(rows,id,reload){busy.value=true;try{const r=await centerAction('connectors',id,'poll',{ids:rows.map(x=>x.record_key)});const failures=r.results.filter(x=>x.outcome!=='SUCCESS');if(failures.length)ElMessage.warning(`${failures.length} 个连接器未完成读取，请查看状态及接入记录`);else ElMessage.success('读取完成');await reload()}finally{busy.value=false}}
async function show(row,id){detail.value=await centerGet('connectors',id,row.record_key)}
// Refresh operational state without clearing selections while the user is preparing a batch.
const timer=setInterval(()=>{if(!visible.value&&!busy.value&&!list.value?.selected?.length)list.value?.load()},30000)
onUnmounted(()=>clearInterval(timer))
</script>
