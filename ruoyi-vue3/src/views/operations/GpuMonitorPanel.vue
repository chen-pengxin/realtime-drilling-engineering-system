<template>
  <el-card class="operations-section">
    <template #header>GPU 处理器</template>
    <el-table :data="rows" :empty-text="collectionError || (checkedAt ? '未取得设备信息' : '待采集')">
      <el-table-column prop="node" label="节点" width="110" />
      <el-table-column prop="name" label="处理器名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="vendor" label="厂商" min-width="120" show-overflow-tooltip />
      <el-table-column label="核心数" width="90"><template #default="{row}">{{ row.cores || '—' }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.status==='UP'?'success':row.status==='DETECTED'?'info':'warning'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="利用率" width="110"><template #default="{row}">{{ metric(row.utilization_percent,'%') }}</template></el-table-column>
      <el-table-column label="显存（MiB）" min-width="150"><template #default="{row}">{{ row.shared_memory?(row.memory_used_mb!=null ? '共享，已用 '+metric(row.memory_used_mb) : '共享内存'):`${metric(row.memory_used_mb)} / ${metric(row.memory_total_mb)}` }}</template></el-table-column>
      <el-table-column label="温度" width="100"><template #default="{row}">{{ metric(row.temperature_c,' °C') }}</template></el-table-column>
      <el-table-column label="功耗" width="100"><template #default="{row}">{{ metric(row.power_w,' W') }}</template></el-table-column>
      <el-table-column label="驱动版本" min-width="140" show-overflow-tooltip><template #default="{row}">{{ row.driver_version || '—' }}</template></el-table-column>
    </el-table>
    <el-descriptions class="gpu-sources" :column="1" border>
      <el-descriptions-item label="算法节点 GPU 训练">{{ training }}</el-descriptions-item>
      <el-descriptions-item label="采集时间">{{ formatPlatformTime(null,null,checkedAt) }}</el-descriptions-item>
      <el-descriptions-item v-for="(label,key) in nodes" :key="key" :label="label+'采集状态'">{{ sourceLabel(snapshot[key]) }}</el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>
<script setup>
import { computed } from 'vue'
import { formatPlatformTime } from '@/utils/platformTime'
const props=defineProps({snapshot:{type:Object,default:()=>({})},collectionError:String,checkedAt:String})
const training=computed(()=>{
  const node=props.snapshot.algorithm
  if (node?.trainingAvailable===true) return '可用'
  if (node?.trainingAvailable===false) return '不可用（当前训练服务未发现可用的 CUDA 设备）'
  return '未知'
})
const nodes={business:'业务节点',algorithm:'算法节点'}
const rows=computed(()=>Object.entries(nodes).flatMap(([key,node])=>(props.snapshot[key]?.devices||[]).map(row=>({...row,node}))))
const labels={UP:'正常',DETECTED:'已识别',NO_DEVICE:'未检测到设备',UNSUPPORTED:'暂不支持',UNKNOWN:'未提供监控',UNAVAILABLE:'采集不可用',TIMEOUT:'采集超时',ERROR:'采集异常'}
const statusLabel=status=>labels[status]||'待采集'
const metric=(value,suffix='')=>value!=null&&Number.isFinite(Number(value))?`${Number(value).toLocaleString('zh-CN',{maximumFractionDigits:1})}${suffix}`:'未提供'
function sourceLabel(node){if(!node)return props.collectionError || '待采集';const count=node.devices?.length||0;return [statusLabel(node.status),count?`${count} 个处理器`:'',node.host||'',node.message||''].filter(Boolean).join('，')}
</script>
<style scoped>.gpu-sources{margin-top:16px}</style>
