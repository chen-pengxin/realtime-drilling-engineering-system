<template><OperationsFrame title="后台维护任务">
 <div class="operations-toolbar"><el-button icon="Refresh" :loading="loading" @click="load">刷新</el-button><el-button @click="showLogs()">调度日志</el-button><router-link to="/access/settings"><el-button>清理保留时间</el-button></router-link></div>
 <el-table :data="rows" v-loading="loading"><el-table-column prop="jobName" label="维护任务" min-width="190" /><el-table-column label="执行计划（北京时间）" min-width="200"><template #default="{row}">{{ describePlan(row.cronExpression) }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='0'?'success':'info'">{{row.running?'执行中':row.status==='0'?'已启用':'已暂停'}}</el-tag></template></el-table-column><el-table-column prop="nextRun" label="下次执行" min-width="180" /><el-table-column label="最近结果" width="110"><template #default="{row}"><el-tag v-if="row.lastResult" :type="row.lastResult.outcome==='SUCCESS'?'success':'danger'">{{row.lastResult.outcome==='SUCCESS'?'成功':'失败'}}</el-tag><span v-else>尚未执行</span></template></el-table-column><el-table-column label="操作" width="230" fixed="right"><template #default="{row}"><el-button link type="primary" @click="edit(row)">配置</el-button><el-button link type="primary" :disabled="row.running" @click="run(row)">执行一次</el-button><el-button link type="primary" @click="showLogs(row)">日志</el-button><el-button v-if="row.lastResult" link type="primary" @click="result=row.lastResult">详情</el-button></template></el-table-column></el-table>
 <p class="operations-caption">仅管理服务检查、临时导出文件清理和过期会话整理。任务默认暂停，可配置计划或手动执行。临时清理保留工程原始文件、模型和测斜版本。</p>
 <el-dialog v-model="open" title="维护任务配置" width="590px" class="operations-dialog"><el-form label-width="100px"><el-form-item label="任务名称">{{form.jobName}}</el-form-item><el-form-item label="执行计划"><el-select v-model="selectedPlan" @change="selectPlan">
   <el-option v-for="plan in presets" :key="plan" :value="plan" :label="describePlan(plan)" />
   <el-option value="custom" label="自定义计划" />
 </el-select></el-form-item>
 <el-form-item v-if="selectedPlan==='custom'" label="调度表达式"><el-input v-model="form.cronExpression" placeholder="例如 0 0/5 * * * ?" /></el-form-item><el-form-item label="状态"><el-radio-group v-model="form.status"><el-radio value="0">启用</el-radio><el-radio value="1">暂停</el-radio></el-radio-group></el-form-item></el-form><template #footer><el-button @click="open=false">取 消</el-button><el-button type="primary" :loading="saving" @click="save">确 定</el-button></template></el-dialog>
 <el-dialog v-model="logOpen" title="调度日志" width="1000px" class="operations-dialog"><el-form :inline="true"><el-form-item label="执行状态"><el-select v-model="logQuery.status" clearable placeholder="全部" style="width:140px"><el-option value="0" label="成功" /><el-option value="1" label="失败" /></el-select></el-form-item><el-form-item label="时间"><el-date-picker v-model="logDates" type="daterange" value-format="YYYY-MM-DD" /></el-form-item><el-form-item><el-button type="primary" @click="searchLogs">搜索</el-button><el-button @click="loadLogs">刷新</el-button><el-button icon="Download" @click="exportLogs">导出</el-button></el-form-item></el-form><el-table :data="logs" v-loading="logLoading"><el-table-column prop="jobName" label="维护任务" width="170" /><el-table-column label="结果" width="80"><template #default="{row}"><el-tag :type="row.status==='0'?'success':'danger'">{{row.status==='0'?'成功':'失败'}}</el-tag></template></el-table-column><el-table-column prop="jobMessage" label="说明" min-width="220" show-overflow-tooltip /><el-table-column :formatter="formatPlatformTime" prop="createTime" label="执行时间" width="180" /><el-table-column label="详情" width="80"><template #default="{row}"><el-button link type="primary" @click="result=row">查看</el-button></template></el-table-column></el-table><pagination v-show="logTotal>0" :total="logTotal" v-model:page="logQuery.pageNum" v-model:limit="logQuery.pageSize" @pagination="loadLogs" /></el-dialog>
 <el-dialog :model-value="!!result" title="执行详情" width="700px" class="operations-dialog" @close="result=null"><pre>{{result?.summaryJson?prettyJson(result.summaryJson):result?.exceptionInfo||result?.jobMessage}}</pre></el-dialog>
</OperationsFrame></template>
<script setup>
import {formatPlatformTime} from '@/utils/platformTime'

import {ref,reactive} from 'vue';import {ElMessage,ElMessageBox} from 'element-plus';import OperationsFrame from './OperationsFrame.vue';import {useOperations,usePagePolling,prettyJson} from './useOperations';import {listOperation,updateOperation,executeOperation,exportOperation} from '@/api/operations';
const {rows,loading,load}=useOperations('jobs');const open=ref(false),saving=ref(false),form=reactive({}),result=ref(null),logOpen=ref(false),logs=ref([]),logTotal=ref(0),logLoading=ref(false),logDates=ref([]),logQuery=reactive({pageNum:1,pageSize:20,status:'',taskCode:''})
const presets = ['0 0/5 * * * ?', '0 0/10 * * * ?', '0 0 3 * * ?']
const selectedPlan = ref('')
function describePlan(expression) {
  const rule = String(expression || '').trim().replace(/\s+/g, ' ')
  const interval = /^0 (?:0|\*)\/(\d+) \* \* \* \?(?: \*)?$/.exec(rule)
  if (interval && 60 % Number(interval[1]) === 0) return '每 ' + Number(interval[1]) + ' 分钟执行一次'
  const daily = /^0 (\d{1,2}) (\d{1,2}) \* \* \?(?: \*)?$/.exec(rule)
  if (daily) return '每天 ' + daily[2].padStart(2, '0') + ':' + daily[1].padStart(2, '0') + ' 执行'
  return '自定义计划'
}
function edit(row) {
  Object.assign(form, row)
  selectedPlan.value = presets.includes(row.cronExpression) ? row.cronExpression : 'custom'
  open.value = true
}
function selectPlan(plan) {
  if (plan !== 'custom') form.cronExpression = plan
}
async function save(){saving.value=true;try{await updateOperation('jobs',form.jobId,{cronExpression:form.cronExpression,status:form.status});open.value=false;await load();ElMessage.success('维护计划已保存')}catch{}finally{saving.value=false}}
async function run(row){try{await ElMessageBox.confirm(`立即执行「${row.jobName}」？`,'执行维护任务',{type:'warning'});await executeOperation(`jobs/${row.jobId}/run`);ElMessage.success('任务已提交');await load();showLogs(row)}catch{}}
function showLogs(row){logQuery.taskCode=row?.taskCode||'';logQuery.pageNum=1;logQuery.status='';logDates.value=[];logOpen.value=true;loadLogs()}
const logParams=()=>({...logQuery,beginTime:logDates.value?.[0],endTime:logDates.value?.[1]})
function searchLogs(){logQuery.pageNum=1;loadLogs()}
async function loadLogs(){logLoading.value=true;try{const r=await listOperation('job-logs',logParams());logs.value=r.rows;logTotal.value=r.total}catch{}finally{logLoading.value=false}}
async function exportLogs(){try{await exportOperation('job-logs',logParams())}catch{}}
usePagePolling(()=>{load();if(logOpen.value)loadLogs()},10000)
</script>
