<template><span class="sr-only" aria-live="polite">{{futureRisk?`预测防碰风险 ${futureRisk.level}`:''}}</span></template>
<script setup>
import {watch,onMounted,onBeforeUnmount} from 'vue'
import {useRouter,useRoute} from 'vue-router'
import {ElMessageBox} from 'element-plus'
import useUserStore from '@/store/modules/user'
import {useDrillingContext} from '../composables/useDrillingContext'
import {useLiveDrilling,clearLiveDrilling} from '../composables/useLiveDrilling'
import {playCollisionAlarm,riskLabel} from '@/utils/realtimeCollision'
import {isEngineeringEvaluationRoute} from '@/utils/engineConfirmation'
import {engineConfirmationOpen} from '../composables/engineConfirmationState'
const router=useRouter(),route=useRoute(),user=useUserStore(),ctx=useDrillingContext(),live=useLiveDrilling(),{futureRisk}=live
let timer,shown='',open=false,disposed=false
const canMonitor=()=>isEngineeringEvaluationRoute(route.path)&&!document.hidden&&!user.demoOnly&&!user.mustChangePassword&&(user.permissions.includes('*:*:*')||user.permissions.includes('drilling:collision:run'))
async function refresh(){if(!disposed&&ctx.fieldId.value&&canMonitor())await live.refresh()}
watch(ctx.fieldId,()=>{if(open)ElMessageBox.close();clearLiveDrilling();shown='';refresh()})
// Page navigation shares the same live snapshot and in-flight scan. Clearing
// it here used to start concurrent full scans for an unchanged wellbore.
watch(()=>route.path,(path,previous)=>{
  if(!isEngineeringEvaluationRoute(path)&&isEngineeringEvaluationRoute(previous||'')){
    clearLiveDrilling();shown='';return
  }
  refresh()
})
watch([futureRisk,engineConfirmationOpen,live.loading,()=>route.path],async ([risk])=>{
  const config=live.configuration.value?.prediction||{},rank={YELLOW:1,ORANGE:2,RED:3}[config.alertMinimumLevel||'YELLOW']||1
  // A refresh clears its transient snapshot before the next response. It is
  // not a resolved alarm and must not re-arm the same well-pair notification.
  if(!risk){if(!live.loading.value&&live.network.value)shown='';return}
  if(risk.rank<rank){shown='';return}
  if(!canMonitor()||engineConfirmationOpen.value)return
  if((!config.popupAlarmEnabled&&!config.soundAlarmEnabled)||open||disposed)return
  const key=`${ctx.fieldId.value}:${live.boreId.value}:${risk.neighborWellId}:${risk.level}:${config.popupAlarmEnabled}:${config.soundAlarmEnabled}`
  if(shown===key)return
  shown=key
  if(config.soundAlarmEnabled)playCollisionAlarm()
  if(!config.popupAlarmEnabled)return
  const alarmField=ctx.fieldId.value,alarmBore=live.boreId.value
  open=true
  try{
    await ElMessageBox.confirm(`井眼 ${live.bore.value?.well_code||''} 的预测钻头位置 MD ${risk.predictedMd.toFixed(1)} m 与邻井 ${risk.neighborWellId} 存在${riskLabel(risk.level)}风险，SF ${Number.isFinite(risk.sf)?risk.sf.toFixed(3):'—'}。进入绕障再规划后，请核对目标点和分段约束。`,'实时防碰预警',{type:'warning',confirmButtonText:'立即进入再规划',cancelButtonText:'暂不处理',closeOnClickModal:false})
    if(alarmField===ctx.fieldId.value&&alarmBore===live.boreId.value&&!engineConfirmationOpen.value)router.push({name:'DrillingReplanning',query:{alert:'collision',wellId:String(live.bore.value?.well_id||''),wellboreId:String(live.boreId.value||''),referenceVersionId:String(live.detail.value?.survey_version_id||''),neighborId:String(risk.neighborWellId),neighborVersionId:String(risk.pair?.neighbor_survey_version_id||''),predictedMd:String(risk.predictedMd)}})
  }catch{}finally{open=false}
})
watch([ctx.fieldId,engineConfirmationOpen,()=>route.path],()=>{if(open&&(!canMonitor()||engineConfirmationOpen.value))ElMessageBox.close()})
function confirmed(){if(canMonitor())live.refresh({force:true})}
function onVisibilityChange(){if(!document.hidden)refresh()}
onMounted(()=>{refresh();timer=setInterval(refresh,30000);document.addEventListener('visibilitychange',onVisibilityChange);window.addEventListener('drilling-engine-confirmed',confirmed);window.addEventListener('drilling-survey-updated',confirmed);window.addEventListener('drilling-survey-version-changed',confirmed);window.addEventListener('drilling-neighbors-changed',confirmed)})
onBeforeUnmount(()=>{disposed=true;clearInterval(timer);document.removeEventListener('visibilitychange',onVisibilityChange);clearLiveDrilling();window.removeEventListener('drilling-engine-confirmed',confirmed);window.removeEventListener('drilling-survey-updated',confirmed);window.removeEventListener('drilling-survey-version-changed',confirmed);window.removeEventListener('drilling-neighbors-changed',confirmed)})
</script>
