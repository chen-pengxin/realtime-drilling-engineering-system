<template>
  <CenterList ref="list" title="告警中心" module="alerts" :types="types" :severities="filterOptions" :statuses="options(['OPEN','ACKNOWLEDGED','RESOLVED'])" :action-width="260">
    <template #toolbar="{selected,fieldId,reload}">
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:alerts:ack']" type="primary" plain icon="Check" :disabled="!selected.length||busy" @click="act(fieldId,'ack',selected,reload)">批量确认</el-button></el-col>
      <el-col :span="1.5"><el-button v-hasPermi="['drilling:alerts:resolve']" type="success" plain icon="CircleCheck" :disabled="!selected.length||busy" @click="act(fieldId,'resolve',selected,reload)">批量解除</el-button></el-col>
    </template>
    <el-table-column prop="title" label="告警内容" min-width="250" show-overflow-tooltip />
    <el-table-column prop="well_name" label="井名" min-width="120" />
    <el-table-column prop="record_type" label="类型" min-width="140"><template #default="{row}">{{ label(row.record_type) }}</template></el-table-column>
    <el-table-column prop="severity" label="等级" width="90" sortable="custom"><template #default="{row}"><el-tag :type="statusType(row.severity)">{{ displayName(row.severity) }}</el-tag></template></el-table-column>
    <el-table-column prop="status" label="状态" width="110" sortable="custom"><template #default="{row}">{{ label(row.status) }}</template></el-table-column>
    <template #actions="{row,fieldId,reload}">
      <el-button link type="primary" @click="detail=row">详情</el-button>
      <el-button v-if="row.status==='OPEN'" v-hasPermi="['drilling:alerts:ack']" link type="primary" :disabled="busy" @click="act(fieldId,'ack',[row],reload)">确认</el-button>
      <el-button v-if="row.status!=='RESOLVED'" v-hasPermi="['drilling:alerts:resolve']" link type="success" :disabled="busy" @click="act(fieldId,'resolve',[row],reload)">解除</el-button>
      <el-button v-if="row.task_id&&user.allowedModules.includes('replanning')" link type="primary" @click="replan(row,fieldId)">再规划</el-button>
    </template>
  </CenterList>
  <el-dialog :model-value="!!detail" title="告警详情" width="800px" class="drilling-dialog" @close="detail=null"><RecordDetails :display-names="{ severity: displayName }" v-if="detail" :value="detail" /><template #footer><el-button @click="detail=null">关闭</el-button></template></el-dialog>
</template>
<script setup>
import {computed,ref} from 'vue'
import {useDisplayDictionary} from '@/views/operations/useDisplayDictionary'
import {useRouter} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import useUserStore from '@/store/modules/user'
import {centerAction} from '@/api/drillingCenters'
import CenterList from '../shared/CenterList.vue'
import RecordDetails from '../shared/RecordDetails.vue'
import {label,options,statusType} from '../shared/labels'
const dictionary = useDisplayDictionary('risk_level')
const names = computed(() => new Map(dictionary.value.map(item => [item.dictValue, item.dictLabel])))
const displayName = code => names.value.get(code) || label(code)
const filterOptions = computed(() => ['YELLOW', 'ORANGE', 'RED'].map(value => ({ value, label: displayName(value) })))
const list=ref(),detail=ref(null),busy=ref(false),router=useRouter(),user=useUserStore()
const types=options(['ANTI_COLLISION_RISK','ANTI_COLLISION_RED','PREDICTED_COLLISION','DATA_QUALITY','CONNECTION_FAILURE','MODEL_FAILURE','TASK_FAILURE'])
async function act(fieldId,action,rows,reload){try{await ElMessageBox.confirm(`确认${action==='ack'?'确认':'解除'}所选 ${rows.length} 条告警？`,'操作确认',{type:'warning'});busy.value=true;await centerAction('alerts',fieldId,action,{ids:rows.map(x=>x.record_key)});ElMessage.success('操作成功');await reload()}catch(e){if(!['cancel','close'].includes(e))console.error(e)}finally{busy.value=false}}
function replan(row,fieldId){let rules={};try{rules=typeof row.rule_snapshot_json==='string'?JSON.parse(row.rule_snapshot_json):row.rule_snapshot_json||{}}catch{};router.push({path:'/drilling/tasks/replanning',query:{fieldId,wellId:row.well_id,alarmId:row.record_id,alert:row.record_type==='PREDICTED_COLLISION'?'collision':undefined,neighborId:row.neighbor_well_id||rules.neighbor_well_id,predictedMd:rules.predicted_md}})}
</script>
