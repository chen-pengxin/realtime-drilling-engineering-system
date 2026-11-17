import {trajectoryIdentity} from '@/utils/wellboreHierarchy'
import { computed, ref } from 'vue'
import { listWellbores, boreNeighbors, trajectoryVersion, registry } from '@/api/drillingRegistry'
import { scanAntiCollisionNetwork } from '@/api/drilling'
import { predictBitPosition, predictedNetworkRisk } from '@/utils/realtimeCollision'
import {trajectoryColors} from '@/utils/trajectoryColors'
import { useDrillingContext } from './useDrillingContext'

// One live snapshot is shared by the dashboard, ingest page and layout monitor.
// Results from an earlier field/request may never overwrite the current one.
const bores=ref([]),boreId=ref(null),detail=ref(null),neighborDetails=ref([]),network=ref(null),prediction=ref(null),futureRisk=ref(null),loading=ref(false),error=ref(''),refreshedAt=ref(null)
const configuration=ref(null)
let sequence=0,pending,lastScanKey='',selectedField=null
const bore=computed(()=>bores.value.find(b=>Number(b.wellbore_id)===Number(boreId.value)))
const latest=computed(()=>detail.value?.stations?.at(-1)||null)
const trajectories=computed(()=>[
  ...(detail.value?.stations?.length?[{...trajectoryIdentity(bore.value),id:'actual',kind:'ACTUAL',label:`${bore.value?.well_code||'正钻井'} / ${bore.value?.wellbore_code||''}`,points:detail.value.plot_stations||detail.value.stations,color:trajectoryColors.ACTUAL}]:[]),
  // A geometric fallback may still be used by the safety calculation when
  // explicitly enabled, but it is never presented as a measured or model
  // trajectory. Only a successful model response gets a future visual line.
  ...(prediction.value?.source==='TRAINED_MODEL'&&prediction.value?.point&&latest.value?[{...trajectoryIdentity(bore.value),id:'forecast',kind:'PREDICTED',label:'模型预测延伸',dash:true,points:[latest.value,prediction.value.point],color:trajectoryColors.PREDICTED}]:[]),
  ...neighborDetails.value.map(v=>({...trajectoryIdentity(v),id:`neighbor-${v.survey_version_id}`,kind:'NEIGHBOR',label:`${v.well_code||v.well_id} / ${v.wellbore_name||v.wellbore_id}`,versionLabel:`v${v.version_no}`,points:v.plot_stations||v.stations,color:trajectoryColors.NEIGHBOR}))
])

export function clearLiveDrilling(){sequence++;pending=null;lastScanKey='';loading.value=false;bores.value=[];boreId.value=null;detail.value=null;neighborDetails.value=[];network.value=null;prediction.value=null;futureRisk.value=null;configuration.value=null;error.value='';refreshedAt.value=null}

export async function selectLiveBore(id){
  const p=useDrillingContext().fieldId.value
  if(p&&id)localStorage.setItem(`drilling.liveBoreId.${p}`,String(id))
  if(Number(id)!==Number(boreId.value)){sequence++;pending=null;lastScanKey='';detail.value=null;neighborDetails.value=[];network.value=null;prediction.value=null;futureRisk.value=null;boreId.value=id}
  return refreshLiveDrilling({force:true})
}

export function refreshLiveDrilling({force=false,wellboreId,incrementalFrom,incrementalTo}={}){
  const ctx=useDrillingContext(),p=ctx.fieldId.value
  if(!p)return Promise.resolve()
  if(selectedField!==p){clearLiveDrilling();selectedField=p}
  if(pending)return pending
  const n=++sequence,valid=()=>n===sequence&&p===ctx.fieldId.value
  loading.value=true
  const operation=(async()=>{
    try{
      const rows=await listWellbores(p)
      if(!valid())return
      bores.value=rows
      const chosen=rows.find(b=>Number(b.wellbore_id)===Number(wellboreId||boreId.value||localStorage.getItem(`drilling.liveBoreId.${p}`)))||rows.find(b=>b.engineering_status==='DRILLING'&&b.actual_version_id)||rows.find(b=>b.actual_version_id)||rows[0]
      boreId.value=chosen?.wellbore_id
      if(!chosen?.actual_version_id){detail.value=null;network.value=null;error.value='当前井眼没有实钻测斜版本';return}
      const [reference,links,confirmation]=await Promise.all([trajectoryVersion(p,chosen.actual_version_id),boreNeighbors(p,chosen.wellbore_id),registry(p,'/engine-confirmation')])
      if(!valid())return
      configuration.value=confirmation.configuration
      const selected=reference.quality_status!=='READY'?[]:links.filter(x=>Number(x.included)&&x.survey_version_id&&x.quality_status==='READY')
      const neighbors=await Promise.all(selected.map(async x=>({...x,...await trajectoryVersion(p,x.survey_version_id,reference.survey_version_id)})))
      if(!valid())return
      detail.value=reference;neighborDetails.value=neighbors;error.value='';refreshedAt.value=new Date()
      const key=JSON.stringify([p,chosen.wellbore_id,reference.survey_version_id,confirmation.configHash,selected.map(x=>x.survey_version_id)])
      if(!force&&key===lastScanKey)return
      network.value=null;futureRisk.value=null;prediction.value=null
      if(reference.quality_status!=='READY'){error.value='当前版本不可用于防碰评价';return}
      if(!confirmation.confirmed){error.value='请先核对并确认当前工程评价配置';window.dispatchEvent(new CustomEvent('drilling-engine-confirmation-required',{detail:{fieldId:p}}));return}
      const predicted=await predictBitPosition({fieldId:p,predictionConfig:confirmation.configuration.prediction,stations:reference.stations||[]})
      if(!valid())return
      prediction.value=predicted
      if(!selected.length){error.value='没有已选且可用于评价的邻井版本；尚未进行防碰评价';lastScanKey=key;return}
      const result=await scanAntiCollisionNetwork({field_id:p,reference_well_id:reference.well_id,reference_survey_version_id:reference.survey_version_id,reference:{stations:predicted.point?[predicted.point]:[]},neighbors:selected.map(x=>({well_id:x.well_id,survey_version_id:x.survey_version_id})),step:Number(ctx.configForm.algorithmDefaults.scanStep||1),mode:'BOTH',incremental_from_md:incrementalFrom??null,incremental_to_md:incrementalTo??null})
      if(!valid())return
      network.value=result;futureRisk.value=predictedNetworkRisk(result,reference.stations?.at(-1)?.md);lastScanKey=key
      if(result.failed_pairs?.length)error.value=`${result.failed_pairs.length} 个井对评价失败，请检查防碰详情`
    }catch(e){if(valid()){error.value=e.message||'实时监测刷新失败';network.value=null;futureRisk.value=null}}
    finally{if(valid())loading.value=false}
  })()
  pending=operation
  operation.finally(()=>{if(pending===operation)pending=null})
  return operation
}

export function useLiveDrilling(){return {bores,boreId,bore,detail,neighborDetails,latest,network,prediction,futureRisk,configuration,trajectories,loading,error,refreshedAt,refresh:refreshLiveDrilling,selectBore:selectLiveBore}}
