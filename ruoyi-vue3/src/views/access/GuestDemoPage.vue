<template><AccessFrame business :title="moduleLabels[module] || '功能演示'"><template #actions><el-tag type="warning" size="large">游客 · 演示模式</el-tag></template>
  <el-alert :title="data.notice || '独立演示数据，仅用于展示功能界面。'" type="warning" :closable="false" show-icon />
  <p class="access-note">访问时段：{{ user.validFrom?.replace('T',' ') }} 至 {{ user.validUntil?.replace('T',' ') }}（北京时间）</p>
  <div class="demo-layout" v-loading="loading"><el-card><h2>测试案例</h2><el-descriptions :column="1"><el-descriptions-item label="区块">测试区块</el-descriptions-item><el-descriptions-item label="井场">测试井场</el-descriptions-item><el-descriptions-item label="当前井眼">测试案例 A / 主井眼</el-descriptions-item><el-descriptions-item label="MD">600 m</el-descriptions-item><el-descriptions-item label="数据来源">系统测试数据</el-descriptions-item></el-descriptions><el-divider /><p class="access-note">{{ descriptions[module] }}</p><el-button disabled style="width:100%">演示模式 · 不执行实际操作</el-button></el-card>
    <el-card class="demo-center"><el-tabs v-model="tab"><el-tab-pane v-if="hasTrajectory" label="3D 可视化" name="3d"><TrajectoryScene v-if="tab==='3d'" :trajectories="tracks" /></el-tab-pane><el-tab-pane label="数据展示" name="data"><el-table :data="data.rows || []"><el-table-column prop="name" label="名称" min-width="140" /><el-table-column prop="value" label="内容" min-width="130" /><el-table-column prop="status" label="状态" min-width="130" /><el-table-column prop="detail" label="说明" min-width="240" /></el-table></el-tab-pane><el-tab-pane v-if="hasTrajectory" label="轨迹数据" name="table"><el-table :data="data.points || []"><el-table-column v-for="(label,key) in {md:'MD（m）',north:'N（m）',east:'E（m）',tvd:'TVD（m）'}" :key="key" :prop="key" :label="label" min-width="100" /></el-table></el-tab-pane></el-tabs></el-card>
    <el-card v-if="hasTrajectory" class="demo-projections"><PredictionPlots :measured="data.points || []" /></el-card>
  </div>
</AccessFrame></template>
<script setup>
import {computed,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import AccessFrame from './AccessFrame.vue'
import TrajectoryScene from '@/components/TrajectoryScene/index.vue'
import PredictionPlots from '@/views/drilling/tasks/prediction/PredictionPlots.vue'
import {trajectoryColors} from '@/utils/trajectoryColors'
import {getDemo} from '@/api/access'
import useUserStore from '@/store/modules/user'
import {moduleLabels} from '@/security/access'
const route=useRoute(),user=useUserStore(),data=ref({}),tab=ref('3d'),loading=ref(false)
const module=computed(()=>route.params.module)
const hasTrajectory=computed(()=>['realtime','anticollision','prediction','replanning','surveys'].includes(module.value))
const tracks=computed(()=>[{id:'demo-a',kind:'ACTUAL',points:data.value.points||[],color:trajectoryColors.ACTUAL},{id:'demo-b',kind:'NEIGHBOR',points:data.value.neighbor||[],color:trajectoryColors.NEIGHBOR}])
const descriptions={realtime:'展示测斜数据、三维轨迹及两个平面投影。',anticollision:'展示井间关系与防碰风险结果样式。',prediction:'展示预测工作区布局；不执行模型推理。',models:'展示模型版本信息；不导入模型或启动训练。',replanning:'展示绕障方案工作区；不执行轨迹求解。',catalog:'展示区块、井场、井、井眼及成果层级。',surveys:'展示测斜轨迹数据及投影。','realtime-data':'展示数据源连接信息；不连接真实设备。',config:'展示工程配置与参数默认值。',files:'展示文件与审计列表；不上传或下载文件。',alerts:'展示告警列表及处理状态。',history:'展示任务历史与输入来源。',quality:'展示数据质量隔离与处理记录。',reports:'展示工程报告列表；不生成或下载报告。',connectors:'展示连接器配置与运行状态。',versions:'展示配置、测斜、模型和方案版本。'}
let revision=0
watch(module,async key=>{const current=++revision;data.value={};tab.value=hasTrajectory.value?'3d':'data';loading.value=true;try{const r=await getDemo(key);if(current===revision)data.value=r.data}catch{ /* Authentication and permissions are reported by the request interceptor. */ }finally{if(current===revision)loading.value=false}},{immediate:true})
</script>
<style scoped>
.demo-layout{display:grid;grid-template-columns:250px minmax(0,1fr) 340px;gap:16px;margin-top:18px;}.demo-layout:not(:has(.demo-projections)){grid-template-columns:260px minmax(0,1fr);}.demo-center :deep(.scene){height:560px;}.demo-projections :deep(.pose-chart){height:280px;}.demo-layout :deep(.el-descriptions__cell){color:var(--el-text-color-primary);}.demo-layout :deep(.el-descriptions__body){background:var(--el-bg-color);}.demo-center{min-width:0;}
@media(max-width:1200px){.demo-layout{grid-template-columns:230px minmax(0,1fr)}.demo-projections{grid-column:1/-1;}}@media(max-width:760px){.demo-layout,.demo-layout:not(:has(.demo-projections)){grid-template-columns:1fr}.demo-center :deep(.scene){height:380px;}}
</style>
