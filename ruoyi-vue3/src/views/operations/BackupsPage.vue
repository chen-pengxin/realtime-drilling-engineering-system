<template>
  <OperationsFrame title="备份与恢复">
    <div class="operations-toolbar">
      <el-button type="primary" :loading="action==='backup'" :disabled="!!action || running" @click="backup">立即备份</el-button>
      <router-link to="/access/settings"><el-button>设置保留周期</el-button></router-link>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <p>保留 {{ days }} 天，每次备份成功后清理到期副本。</p>
    <el-alert v-if="loadError" :title="loadError" type="warning" :closable="false"/>
    <el-card class="operations-section">
      <template #header>最近可用备份</template>
      <p v-if="latest">完成时间 {{ time(latest.created) }} · 备份编号 {{ latest.id }}</p>
      <el-table :data="scopeRows" :empty-text="emptyText">
        <el-table-column prop="label" label="备份范围"/>
        <el-table-column prop="status" label="备份结果"/>
        <el-table-column prop="files" label="文件数"/>
        <el-table-column prop="size" label="大小"/>
      </el-table>
    </el-card>
    <el-card class="operations-section">
      <template #header>备份副本</template>
      <el-table :data="records" :empty-text="emptyText">
        <el-table-column prop="id" label="备份编号" min-width="215"/>
        <el-table-column label="完成时间" min-width="170"><template #default="{row}">{{ time(row.created) }}</template></el-table-column>
        <el-table-column label="操作人" min-width="130"><template #default="{row}">{{ row.actor || '历史未记录' }}</template></el-table-column>
        <el-table-column v-for="(label,key) in scopes" :key="key" :label="label" min-width="120"><template #default="{row}">{{ scopeStatus(row.scopes?.[key]) }}</template></el-table-column>
        <el-table-column label="恢复" min-width="145"><template #default="{row}">
          <el-button link type="primary" :disabled="!!action || running || row.unavailable" @click="restore(row)">恢复到独立副本</el-button>
        </template></el-table-column>
      </el-table>
    </el-card>
    <el-card class="operations-section">
      <template #header>最近操作记录</template>
      <el-table :data="historyRows" :empty-text="loadError?'状态未知':'暂无已记录的操作'">
        <el-table-column label="操作时间" min-width="170"><template #default="{row}">{{ time(row.finished || row.started) }}</template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="{row}">{{ row.action==='restore'?'恢复':'备份' }}</template></el-table-column>
        <el-table-column prop="actor" label="操作人" min-width="140"/>
        <el-table-column label="结果" width="95"><template #default="{row}"><el-tag :type="row.outcome==='SUCCESS'?'success':row.outcome==='FAILURE'?'danger':'info'">{{ outcomes[row.outcome] }}</el-tag></template></el-table-column>
        <el-table-column prop="backupId" label="备份编号" min-width="215"/>
        <el-table-column label="结果说明" min-width="230"><template #default="{row}">
          {{ row.reason ? [stages[row.stage],row.reason].filter(Boolean).join(' · ') : row.warning || (row.recovery?'独立副本 '+row.recovery:'—') }}
        </template></el-table-column>
      </el-table>
    </el-card>
  </OperationsFrame>
</template>
<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { formatPlatformTime } from '@/utils/platformTime'
import OperationsFrame from './OperationsFrame.vue'

const url='/api/operations/backups'
const scopes={database:'业务数据库',uploads:'工程文件',models:'模型文件'}
const stages={database:'数据库',uploads:'工程文件',models:'模型文件',verify:'完整性校验'}
const outcomes={SUCCESS:'成功',FAILURE:'失败',RUNNING:'执行中'}
const records=ref([]),history=ref([]),days=ref(30),loading=ref(false),loadError=ref(''),action=ref('')
const latest=computed(()=>records.value.find(row=>!row.unavailable))
const emptyText=computed(()=>loadError.value?'状态未知':loading.value?'正在读取':'暂无成功备份')
const historyRows=computed(()=>[...history.value].sort((a,b)=>Date.parse(b.finished||b.started)-Date.parse(a.finished||a.started)).slice(0,100))
const running=computed(()=>history.value.some(row=>row.outcome==='RUNNING'))
const time=value=>formatPlatformTime(null,null,value)
const size=bytes=>bytes===0?'0 B':bytes<1048576?(bytes/1024).toFixed(1)+' KiB':(bytes/1048576).toFixed(1)+' MiB'
const scopeStatus=scope=>!scope?'无法读取':scope.files===0?'已备份（空目录）':'已备份'
const scopeRows=computed(()=>latest.value?Object.entries(scopes).map(([key,label])=>{
  const scope=latest.value.scopes?.[key]
  return {label,status:scopeStatus(scope),files:scope?.files??'—',size:scope?size(scope.bytes):'—'}
}):[])
let poll,closed=false,latestRequest=0
async function load(){
  const sequence=++latestRequest
  clearTimeout(poll)
  loading.value=true
  try{
    const {data}=await request({url})
    if(sequence!==latestRequest)return
    records.value=data.records;history.value=data.history;days.value=data.days;loadError.value=data.inventoryError || ''
  }catch{
    if(sequence===latestRequest){loadError.value='备份状态读取失败，请刷新重试';records.value=[];history.value=[]}
  }finally{
    if(sequence===latestRequest){
      loading.value=false
      if(!closed && (action.value || running.value))poll=setTimeout(load,2000)
    }
  }
}
async function perform(kind,path,body) {
  action.value=kind
  poll=setTimeout(load,800)
  try{
    const {data}=await request({url:path,method:'post',data:body,timeout:0})
    if(data.warning) ElMessage.warning(data.warning)
    else if(kind==='backup') ElMessage.success('备份已完成')
    else await ElMessageBox.alert('独立副本已生成：recoveries/'+data.recovery,'恢复完成')
  }catch{ /* request displays the server's diagnostic */ }
  finally{action.value='';await load()}
}
const backup=()=>perform('backup',url)
async function restore(row){
  const details=Object.entries(scopes).map(([key,label])=>label+' '+row.scopes[key].files+' 个文件').join('、')
  const answer=await ElMessageBox.prompt(
    '将从 '+time(row.created)+' 的备份恢复'+details+'，写入新的独立副本。线上数据继续使用。请输入备份编号 '+row.id,
    '确认恢复范围',{inputValidator:v=>v===row.id || '备份编号不匹配'}).catch(()=>null)
  if(answer)await perform('restore',url+'/'+row.id+'/restore',{confirmation:answer.value})
}
onMounted(load)
onBeforeUnmount(()=>{closed=true;clearTimeout(poll)})
</script>
