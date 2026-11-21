import {trajectoryIdentity,wellboreName} from '@/utils/wellboreHierarchy'
import { computed, ref } from 'vue'
import { listWellbores, boreNeighbors, trajectoryVersion, registry } from '@/api/drillingRegistry'
import { scanAntiCollisionNetwork } from '@/api/drilling'
import { predictBitPosition, predictedNetworkRisk } from '@/utils/realtimeCollision'
import {trajectoryColors} from '@/utils/trajectoryColors'
import { buildEvaluationStations } from '@/utils/liveEvaluationStations'
import { neighborEvaluationMessage } from '@/utils/neighborEvaluationMessage'
import { isEvaluationNeighbor, hasIncompatibleNeighborSelection } from '@/utils/neighborSelection'
import { liveWellboresForField, liveRequestMessage, incompatibleNeighborNotice } from '@/utils/liveDrillingSelection'
import { useDrillingContext } from './useDrillingContext'

// One live snapshot is shared by the dashboard, ingest page and layout monitor.
// Results from an earlier field/request may never overwrite the current one.
const bores=ref([]),boreId=ref(null),detail=ref(null),neighborDetails=ref([]),network=ref(null),prediction=ref(null),futureRisk=ref(null),loading=ref(false),error=ref(''),refreshedAt=ref(null)
const configuration=ref(null)
const neighborNotice=ref('')
let sequence=0,pending,lastScanKey='',selectedField=null
const bore=computed(()=>bores.value.find(b=>Number(b.wellbore_id)===Number(boreId.value)))
const latest=computed(()=>detail.value?.stations?.at(-1)||null)
const trajectories=computed(()=>[
  ...(detail.value?.stations?.length?[{...trajectoryIdentity(bore.value),id:'actual',kind:'ACTUAL',label:`${bore.value?.well_code||'正钻井'} / ${wellboreName(bore.value)}`,points:detail.value.plot_stations||detail.value.stations,color:trajectoryColors.ACTUAL}]:[]),
  // Only a successful trained-model response gets a future visual line.
  // Missing or failed inference leaves the future portion empty and keeps
  // the evaluation on the measured version.
  ...(prediction.value?.source==='TRAINED_MODEL'&&prediction.value?.point&&latest.value?[{...trajectoryIdentity(bore.value),id:'forecast',kind:'PREDICTED',label:'模型预测延伸',dash:true,points:[latest.value,prediction.value.point],color:trajectoryColors.PREDICTED}]:[]),
  ...neighborDetails.value.map(v=>({...trajectoryIdentity(v),id:`neighbor-${v.survey_version_id}`,kind:'NEIGHBOR',trajectory_kind:v.trajectory_kind||'ACTUAL',label:`${v.well_code||v.well_id} / ${v.wellbore_name||v.wellbore_id}`,versionLabel:`v${v.version_no}`,points:v.plot_stations||v.stations,color:trajectoryColors.NEIGHBOR}))
])

export function clearLiveDrilling(){sequence++;pending=null;lastScanKey='';loading.value=false;bores.value=[];boreId.value=null;useDrillingContext().liveWellboreId.value=null;detail.value=null;neighborDetails.value=[];network.value=null;prediction.value=null;futureRisk.value=null;configuration.value=null;error.value='';neighborNotice.value='';refreshedAt.value=null}

export async function selectLiveBore(id){
  const p=useDrillingContext().fieldId.value
  if(p&&id)localStorage.setItem(`drilling.liveBoreId.${p}`,String(id))
  if(Number(id)!==Number(boreId.value)){sequence++;pending=null;lastScanKey='';detail.value=null;neighborDetails.value=[];network.value=null;prediction.value=null;futureRisk.value=null;neighborNotice.value='';boreId.value=id}
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
      // Background refresh errors belong to the live view. A late response
      // must not raise a global toast on the directory after navigation.
      const silent={silentError:true}
      const rows=await listWellbores(p,silent)
      if(!valid())return
      const ownedRows=liveWellboresForField(rows,p)
      bores.value=ownedRows
      const chosen=ownedRows.find(b=>Number(b.wellbore_id)===Number(wellboreId||boreId.value||localStorage.getItem(`drilling.liveBoreId.${p}`)))||ownedRows.find(b=>b.engineering_status==='DRILLING'&&b.actual_version_id)||ownedRows.find(b=>b.actual_version_id)||ownedRows[0]
      if(Number(chosen?.wellbore_id)!==Number(boreId.value)){
        detail.value=null;neighborDetails.value=[];network.value=null;prediction.value=null;futureRisk.value=null;neighborNotice.value='';lastScanKey=''
      }
      boreId.value=chosen?.wellbore_id
      if(chosen)localStorage.setItem(`drilling.liveBoreId.${p}`,String(chosen.wellbore_id))
      ctx.liveWellboreId.value=chosen?.wellbore_id||null
      if(!chosen?.actual_version_id){detail.value=null;network.value=null;error.value='当前井眼没有实钻测斜版本';return}
      const settled=promise=>promise.then(value=>({value}),error=>({error}))
      const linksRequest=settled(boreNeighbors(p,chosen.wellbore_id,silent))
      const confirmationRequest=settled(registry(p,'/engine-confirmation','get',undefined,undefined,silent))
      const reference=await trajectoryVersion(p,chosen.actual_version_id,undefined,silent)
      if(!valid())return
      // Measured data remains visible even if neighbor discovery, coordinate
      // conversion or the evaluation configuration cannot be loaded.
      if(Number(detail.value?.survey_version_id)!==Number(reference.survey_version_id)){
        prediction.value=null;futureRisk.value=null;network.value=null
      }
      detail.value=reference;refreshedAt.value=new Date();error.value='';neighborNotice.value=''
      const [linksResult,confirmationResult]=await Promise.all([linksRequest,confirmationRequest])
      if(!valid())return
      if(linksResult.error||confirmationResult.error){
        neighborDetails.value=[];network.value=null;futureRisk.value=null
        error.value=liveRequestMessage(linksResult.error||confirmationResult.error);return
      }
      const links=linksResult.value,confirmation=confirmationResult.value
      configuration.value=confirmation.configuration
      const selected=reference.quality_status!=='READY'?[]:links.filter(isEvaluationNeighbor)
      const neighborResults=await Promise.all(selected.map(async x=>settled(trajectoryVersion(p,x.survey_version_id,reference.survey_version_id,silent).then(value=>({...x,...value})))))
      if(!valid())return
      neighborDetails.value=neighborResults.filter(result=>!result.error).map(result=>result.value)
      neighborNotice.value=incompatibleNeighborNotice(links)
      if(neighborResults.some(result=>result.error)){
        network.value=null;futureRisk.value=null;error.value='部分邻井轨迹读取失败，尚未完成防碰评价';return
      }
      if(hasIncompatibleNeighborSelection(links)){
        network.value=null;futureRisk.value=null;error.value='已选邻井坐标基准不一致，请调整邻井关系';return
      }
      // The collision request must retain the complete measured history.  A
      // previous implementation sent only the optional prediction point,
      // which made a non-zero-MD survey look like an incomplete MD=0 history
      // to the native uncertainty model.
      const measuredStations=Array.isArray(reference.stations)?reference.stations:[]
      const lastMeasured=measuredStations.at(-1)
      if(!confirmation.confirmed){network.value=null;futureRisk.value=null;prediction.value=null;error.value='请先核对并确认当前工程评价配置';window.dispatchEvent(new CustomEvent('drilling-engine-confirmation-required',{detail:{fieldId:p}}));return}
      // Preserve the last evaluation when unchanged inputs reuse its cache.
      if(reference.quality_status!=='READY'){error.value='当前版本不可用于防碰评价';return}
      const predicted=await predictBitPosition({fieldId:p,predictionConfig:confirmation.configuration.prediction,stations:reference.stations||[]})
      if(!valid())return
      prediction.value=predicted
      const predictedPoint=predicted?.point&&lastMeasured&&Number(predicted.point.md)>Number(lastMeasured.md)+1e-9
        ? predicted.point
        : null
      const requestStations=buildEvaluationStations(measuredStations,predictedPoint)
      const predictionKey=predictedPoint?[predictedPoint.md,predictedPoint.inclination,predictedPoint.azimuth,predictedPoint.north,predictedPoint.east,predictedPoint.tvd]:null
      const key=JSON.stringify([p,chosen.wellbore_id,reference.survey_version_id,confirmation.configHash,selected.map(x=>x.survey_version_id),predictionKey])
      if(!force&&key===lastScanKey){if(!selected.length&&!neighborNotice.value)neighborNotice.value=neighborEvaluationMessage(links);return}
      error.value='';network.value=null;futureRisk.value=null
      if(!selected.length){
        neighborNotice.value=neighborNotice.value||neighborEvaluationMessage(links)
        lastScanKey=key;return
      }
      if(measuredStations.length<2){error.value='当前实钻版本至少需要两个有效测点；尚未进行防碰评价';lastScanKey=key;return}
      const result=await scanAntiCollisionNetwork({field_id:p,reference_well_id:reference.well_id,reference_survey_version_id:reference.survey_version_id,reference:{stations:requestStations},neighbors:selected.map(x=>({well_id:x.well_id,survey_version_id:x.survey_version_id})),step:Number(ctx.configForm.algorithmDefaults.scanStep||1),mode:'BOTH',incremental_from_md:incrementalFrom??null,incremental_to_md:incrementalTo??null},{silentError:true})
      if(!valid())return
      network.value=result;futureRisk.value=predictedNetworkRisk(result,reference.stations?.at(-1)?.md);lastScanKey=key
      if(result.failed_pairs?.length)error.value=result.failed_pairs.find(p=>p.scope==='REFERENCE')?.reason||`${result.failed_pairs.length} 个井对评价失败，请检查防碰详情`
    }catch(e){if(valid()){error.value=liveRequestMessage(e,'实时监测刷新失败');network.value=null;futureRisk.value=null}}
    finally{if(valid())loading.value=false}
  })()
  pending=operation
  operation.finally(()=>{if(pending===operation)pending=null})
  return operation
}

export function useLiveDrilling(){return {bores,boreId,bore,detail,neighborDetails,latest,network,prediction,futureRisk,configuration,trajectories,loading,error,neighborNotice,refreshedAt,refresh:refreshLiveDrilling,selectBore:selectLiveBore}}
