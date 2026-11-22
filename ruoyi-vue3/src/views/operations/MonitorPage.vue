<template>
  <OperationsFrame title="运行监控">
    <template #actions><div class="monitor-head">
      <span v-if="lastCheck" class="operations-caption">最近成功刷新 {{ time(lastCheck) }}</span>
      <el-button icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div></template>
    <el-alert v-if="error" :title="error" type="warning" show-icon :closable="false" />
    <div class="operations-grid">
      <el-card v-for="(name,key) in serviceNames" :key="key" class="operations-card">
        <template #header>{{ name }}</template>
        <el-tag :type="serviceTag(service(key).status)">{{ serviceLabel(service(key).status) }}</el-tag>
        <p v-if="service(key).message" class="operations-caption">{{ service(key).message }}</p>
        <p class="operations-caption">{{ key==='realtime'?'轮询用时':'响应延迟' }} {{ service(key).latencyMs!=null ? service(key).latencyMs+' ms' : '—' }}</p>
        <p class="operations-caption">{{ key==='realtime' ? '最近活动时间' : '上次正常响应时间' }} {{ time(service(key).heartbeatAt || service(key).lastSuccessAt) }}</p>
      </el-card>
    </div>
    <p class="operations-caption">主机采样 {{ data.serverError || time(data.serverCheckedAt) }}</p>
    <div class="operations-grid">
      <el-card v-for="metric in metrics" :key="metric.name" class="operations-card">
        <template #header>{{ metric.name }}</template>
        <el-progress v-if="metric.used!=null" type="dashboard" :percentage="percent(metric.used)" />
        <p v-else>{{ unavailable }}</p>
        <p class="operations-caption">{{ metric.detail }}</p>
      </el-card>
    </div>
    <GpuMonitorPanel :snapshot="data.gpu" :collection-error="data.gpuError || (error ? '采集状态未知' : '')" :checked-at="data.gpuCheckedAt"/>
    <el-card class="operations-section"><template #header>服务器信息</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="操作系统">{{ data.server?.sys?.osName || '—' }} / {{ data.server?.sys?.osArch || '—' }}</el-descriptions-item>
        <el-descriptions-item label="Java 版本">{{ data.server?.jvm?.version || '—' }}</el-descriptions-item>
        <el-descriptions-item label="运行时间">{{ data.server?.jvm?.runTime || '—' }}</el-descriptions-item>
        <el-descriptions-item label="自动刷新">{{ interval }} 秒</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-card class="operations-section"><template #header>磁盘使用</template>
      <el-table :data="data.server?.sysFiles || []" :empty-text="unavailable">
        <el-table-column prop="dirName" label="挂载目录" min-width="220"/><el-table-column prop="typeName" label="类型"/>
        <el-table-column prop="total" label="总量"/><el-table-column prop="used" label="已用"/><el-table-column prop="free" label="可用"/>
        <el-table-column label="使用率" min-width="140"><template #default="{row}"><el-progress :percentage="percent(row.usage)"/></template></el-table-column>
      </el-table>
    </el-card>
    <el-card class="operations-section"><template #header>实时数据连接</template>
      <el-table :data="data.connectors || []" :empty-text="data.connectorsError || (data.connectors ? '暂无已配置的实时连接' : unavailable)">
        <el-table-column prop="connectorName" label="连接名称"/><el-table-column prop="fieldName" label="所属井场"/>
        <el-table-column label="协议"><template #default="{row}">{{ sourceLabels[row.connectorType] || row.connectorType }}</template></el-table-column>
        <el-table-column label="接入状态"><template #default="{row}"><el-tag :type="row.status==='CONNECTED'?'success':['ERROR','STALE','RECONNECTING'].includes(row.status)?'warning':'info'">{{ connectorLabels[row.status] || row.status }}</el-tag></template></el-table-column>
        <el-table-column label="上次成功采集时间" min-width="165"><template #default="{row}">{{ time(row.lastHeartbeat) }}</template></el-table-column>
        <el-table-column label="异常摘要" min-width="200"><template #default="{row}">{{ row.status==='STALE'?'超过心跳时限，尚未收到新数据':row.lastError || '—' }}</template></el-table-column>
      </el-table>
    </el-card>
    <el-card class="operations-section"><template #header>后台工程任务</template>
      <el-table :data="taskRows" :empty-text="data.tasksError || unavailable">
        <el-table-column prop="label" label="任务类型"/>
        <el-table-column prop="running" label="运行中"/><el-table-column prop="queued" label="待执行"/>
        <el-table-column label="累计异常"><template #default="{row}"><el-tag :type="row.failed?'danger':'info'">{{ row.failed }}</el-tag></template></el-table-column>
        <el-table-column label="各状态数量" min-width="260"><template #default="{row}">{{ row.detail || '暂无任务记录' }}</template></el-table-column>
      </el-table>
    </el-card>
  </OperationsFrame>
</template>
<script setup>
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { listOperation, getReferenceDictionary } from '@/api/operations'
import { formatPlatformTime } from '@/utils/platformTime'
import OperationsFrame from './OperationsFrame.vue'
import GpuMonitorPanel from './GpuMonitorPanel.vue'
import { useDisplayDictionary } from './useDisplayDictionary'

const data = ref({}), loading = ref(false), error = ref(''), lastCheck = ref(''), interval = ref(30)
const sourceLabels = ref({}), taskStates = useDisplayDictionary('task_state')
const serviceNames = {frontend:'前端服务',java:'Java 后端',database:'MySQL 数据库',algorithm:'Python 算法服务',realtime:'实时连接服务'}
const connectorLabels = {CONNECTED:'已连接',STALE:'心跳超时',ENABLED:'等待接入',DISABLED:'已停用',ERROR:'异常',RECONNECTING:'重连中'}
const types = {PREDICTION:'预测任务',TRAINING:'训练任务',REPLANNING:'轨迹再规划'}
const labels = {MODEL_NOT_CONFIGURED:'未配置模型',INFEASIBLE:'无可行解',BEST_EFFORT:'近似解',SUCCESS:'成功',FAILED:'失败',RUNNING:'运行中',STOPPED:'已停止'}
const time = v => formatPlatformTime(null,null,v)
const percent = n => Math.min(100,Math.max(0,Math.round(n)))
const service = key => data.value.services?.[key] || {}
const serviceLabel = state => ({UP:'正常',DOWN:'不可用',PENDING:'等待首次轮询'}[state] || (error.value?'状态未知':'待检查'))
const serviceTag = state => state==='UP'?'success':state==='DOWN'?'danger':'info'
const unavailable = computed(()=>data.value.serverError || (error.value?'数据不可用':'待采集'))
const metrics = computed(()=>{
  const {cpu,mem,jvm} = data.value.server || {}
  return [
    {name:'CPU',used:cpu?.free!=null ? 100-cpu.free : null,detail:cpu ? cpu.cpuNum+' 个逻辑处理器' : ''},
    {name:'物理内存',used:mem?.usage,detail:mem ? '已用 '+mem.used+' / '+mem.total+' GB' : ''},
    {name:'Java 内存',used:jvm?.usage,detail:jvm ? '已用 '+jvm.used+' / '+jvm.total+' MB' : ''}
  ]
})
const taskRows = computed(()=>{
  if (!Array.isArray(data.value.tasks)) return []
  return Object.entries(types).map(([type,label])=>{
    const rows = data.value.tasks.filter(r=>r.taskType===type)
    const sum = states=>rows.reduce((n,r)=>n+(states.includes(r.status)?Number(r.count):0),0)
    const name = status=>taskStates.value.find(x=>x.dictValue===status)?.dictLabel || labels[status] || status
    return { label, running:sum(['RUNNING']), queued:sum(['QUEUED','PENDING','SUBMITTED']),
      failed:sum(['FAILED','ERROR','INFEASIBLE','MODEL_NOT_CONFIGURED']),
      detail:rows.map(r=>name(r.status)+' '+r.count).join('，') }
  })
})
let timer, active=true, mounted=false, received=0
function schedule(){
  clearTimeout(timer)
  if (active && !document.hidden) {
    const first = data.value.serverError==='正在首次采集' || data.value.gpuError==='正在首次采集'
    timer=setTimeout(load,first?1500:interval.value*1000)
  }
}
async function load(){
  if (loading.value || !active) return
  clearTimeout(timer)
  if (received && Date.now()-received>(interval.value+5)*1000) {
    data.value={}; error.value='上次数据已过期，正在重新检查'
  }
  loading.value=true
  try {
    const snapshot=(await listOperation('monitor')).data
    if (!snapshot?.services || !snapshot.checkedAt) throw new Error()
    data.value=snapshot; lastCheck.value=snapshot.checkedAt
    interval.value=Math.max(5,Number(snapshot.refreshSeconds)||30)
    received=Date.now(); error.value=''
  } catch {
    data.value={}
    error.value=lastCheck.value?'刷新失败，上次数据已过期，当前状态未知':'监控数据获取失败，当前状态未知'
  } finally { loading.value=false; schedule() }
}
function resume(){if(active && !document.hidden) load()}
onMounted(()=>{
  mounted=true; document.addEventListener('visibilitychange',resume); load()
  getReferenceDictionary('drilling_data_source').then(r=>{sourceLabels.value=Object.fromEntries(r.data.map(x=>[x.dictValue,x.dictLabel]))}).catch(()=>{})
})
onActivated(()=>{const returning=!active; active=true; if(mounted && returning) resume()})
onDeactivated(()=>{active=false;clearTimeout(timer)})
onBeforeUnmount(()=>{active=false;clearTimeout(timer);document.removeEventListener('visibilitychange',resume)})
</script>
