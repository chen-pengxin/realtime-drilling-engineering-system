import {trajectoryIdentity} from '@/utils/wellboreHierarchy'
import {computed,ref,watch,onBeforeUnmount} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {useRoute} from 'vue-router'
import {listWellbores,boreVersions,trajectoryVersion,boreNeighbors,registry,downloadBlob,taskHistory,taskHistoryDetail} from '@/api/drillingRegistry'
import {useDrillingContext} from './useDrillingContext'
import {exportDrillingReport} from '@/api/drilling'
import {trajectoryColors} from '@/utils/trajectoryColors'

export function useTrajectoryTask(task){
  const context=useDrillingContext(),route=useRoute(),bores=ref([]),boreId=ref(),versions=ref([]),versionId=ref(),detail=ref(),design=ref(),neighbors=ref([]),neighborDetails=ref([]),busy=ref(false),result=ref(),title=ref(task==='anticollision'?'防碰评价':'轨迹再规划'),historyOpen=ref(false),history=ref([])
  const workspaceDraft=ref(null),historySnapshot=ref(null)
  let serial=0,versionSerial=0,neighborSerial=0,disposed=false,hydrating=false
  const parse=value=>typeof value==='string'?JSON.parse(value):value
  const bore=computed(()=>bores.value.find(b=>b.wellbore_id===boreId.value)),selectedVersion=computed(()=>versions.value.find(v=>v.survey_version_id===versionId.value))
  const hiddenRoots=['采气二厂煤岩气完井资料+设计（11口）','采气二厂煤岩气完井资料+设计(11口)','Volve数据集','well_control_iscwsa']
  const humanName=value=>{let text=String(value||'').replaceAll('\\','/');for(const root of hiddenRoots)text=text.replaceAll(root,'');return text.split('/').filter(Boolean).pop()?.trim()||''}
  const wellLabel=b=>humanName(b?.well_name)||`井 ${humanName(b?.well_code)||'未命名'}`
  const wellboreLabel=b=>{const name=humanName(b?.wellbore_name),code=humanName(b?.wellbore_code),well=humanName(b?.well_name),wellCode=humanName(b?.well_code);if(!name||name===well||name===wellCode||code===well||code===wellCode)return '主井眼';return name||`井眼 ${code}`}
  const label=b=>[humanName(b?.block_name)||'未注明区块',humanName(b?.field_name)||'未注明井场',wellLabel(b),wellboreLabel(b)].filter((part,index,all)=>part&&part!==all[index-1]).join(' / ')
  // Keep the task selector aligned with the engineering tree.  IDs are used
  // as option values while labels stay readable and never expose source
  // directory aliases.
  const wellboreOptions=computed(()=>{
    const blocks=new Map()
    for(const row of bores.value){
      if(String(row.well_code||'').startsWith('__ARCHIVED_ALIAS_'))continue
      const blockKey=String(row.block_id||'block'),fieldKey=String(row.field_id||'field'),wellKey=String(row.well_id)
      if(!blocks.has(blockKey))blocks.set(blockKey,{value:blockKey,label:humanName(row.block_name)||'未注明区块',children:new Map()})
      const block=blocks.get(blockKey)
      if(!block.children.has(fieldKey))block.children.set(fieldKey,{value:fieldKey,label:humanName(row.field_name)||'未注明井场',children:new Map()})
      const field=block.children.get(fieldKey)
      if(!field.children.has(wellKey))field.children.set(wellKey,{value:wellKey,label:wellLabel(row),children:[]})
      field.children.get(wellKey).children.push({value:String(row.wellbore_id),label:wellboreLabel(row)})
    }
    return [...blocks.values()].map(block=>({...block,children:[...block.children.values()].map(field=>({...field,children:[...field.children.values()]}))}))
  })
  const borePath=computed({
    get(){const row=bore.value;return row?[String(row.block_id||'block'),String(row.field_id||'field'),String(row.well_id),String(row.wellbore_id)]:[]},
    set(path){boreId.value=Array.isArray(path)&&path.length?Number(path[path.length-1]):undefined}
  })
  const selectedNeighbors=computed(()=>neighbors.value.filter(n=>n.included&&n.relationship_confirmed&&n.survey_version_id&&n.quality_status==='READY'&&n.source_review_status==='CONFIRMED'))
  const baseTrajectories=computed(()=>[
    ...(detail.value?.stations?.length?[{...trajectoryIdentity(bore.value),id:'actual',kind:'ACTUAL',label:'实钻轨迹',versionLabel:`${label(bore.value)} · v${detail.value.version_no}`,points:detail.value.plot_stations||detail.value.stations,color:trajectoryColors.ACTUAL}]:[]),
    ...(design.value?.stations?.length?[{...trajectoryIdentity(bore.value),id:'design',kind:'DESIGN',label:'原始设计方案',versionLabel:`v${design.value.version_no}`,points:design.value.plot_stations||design.value.stations,color:trajectoryColors.DESIGN}]:[]),
    ...neighborDetails.value.map(v=>({...trajectoryIdentity(neighbors.value.find(n=>n.survey_version_id===v.survey_version_id)),id:`bore-${v.wellbore_id}`,kind:'NEIGHBOR',label:label(neighbors.value.find(n=>n.survey_version_id===v.survey_version_id)||{}),versionLabel:`v${v.version_no}`,points:v.plot_stations||v.stations,color:trajectoryColors.NEIGHBOR}))
  ])
  async function loadBores(){
    const p=context.fieldId.value,n=++serial;versionSerial++;neighborSerial++
    bores.value=[];versions.value=[];detail.value=null;design.value=null;result.value=null;neighbors.value=[];neighborDetails.value=[];workspaceDraft.value=null;historySnapshot.value=null
    if(!p)return
    try{
      const [rows,saved]=await Promise.all([listWellbores(p),registry(p,`/workspace/${task}`)])
      if(disposed||n!==serial)return
      bores.value=rows
      const draft=parse(saved?.payload_json)||saved?.payload||{}
      const chosen=rows.find(b=>String(b.wellbore_id)===String(route.query.wellboreId||''))||rows.find(b=>String(b.well_id)===String(route.query.wellId||''))||rows.find(b=>Number(b.wellbore_id)===Number(draft.wellboreId))||rows.find(b=>Number(b.wellbore_id)===Number(boreId.value))||rows.find(b=>b.engineering_status==='DRILLING'&&b.actual_version_id)||rows.find(b=>b.actual_version_id)||rows[0]
      // Keep the watcher muted while the initial bore and its draft are
      // hydrated. loadVersions is the single owner of version loading; this
      // avoids the synchronous boreId watcher issuing a second identical
      // request for every page entry.
      hydrating=true;boreId.value=chosen?.wellbore_id
      context.activeWellboreId.value=chosen?.wellbore_id||null
      if(chosen?.wellbore_id)localStorage.setItem('drilling.activeWellboreId',String(chosen.wellbore_id))
      title.value=saved?.title||(task==='anticollision'?'防碰评价':'轨迹再规划')
      const alert=route.query.alert==='collision'
      await loadVersions(alert?{surveyVersionId:route.query.referenceVersionId}:chosen?.wellbore_id===draft.wellboreId?draft:null)
      hydrating=false
      if(!disposed&&n===serial)workspaceDraft.value=alert?null:draft
    }catch(e){hydrating=false;if(n===serial&&!disposed)ElMessage.error(e.message||'井眼加载失败')}
  }
  async function loadVersions(draft){const p=context.fieldId.value,b=boreId.value,n=++versionSerial;neighborSerial++;versions.value=[];detail.value=null;design.value=null;result.value=null;historySnapshot.value=null;neighbors.value=[];neighborDetails.value=[];if(!p||!b)return;try{const [vs,ds,ns]=await Promise.all([boreVersions(p,b,'ACTUAL'),boreVersions(p,b,'DESIGN'),boreNeighbors(p,b)]);if(n!==versionSerial||disposed)return;versions.value=vs;neighbors.value=ns.map(r=>{const saved=draft?.neighbors?.find(x=>Number(x.wellboreId)===Number(r.wellbore_id));const usable=!!r.survey_version_id&&r.quality_status==='READY'&&r.source_review_status==='CONFIRMED';return {...r,included:draft?.neighbors?!!saved:!!r.included&&usable,usable,survey_version_id:saved?.versionId||r.survey_version_id,version_policy:saved?'PINNED':r.version_policy}});const current=vs.find(v=>Number(v.survey_version_id)===Number(draft?.surveyVersionId))||vs.find(v=>Number(v.is_active))||vs[0];versionId.value=current?.survey_version_id;const currentDetail=current?await trajectoryVersion(p,current.survey_version_id):null;if(n!==versionSerial||disposed)return;detail.value=currentDetail;const d=ds.find(v=>Number(v.is_active))||ds[0];const designDetail=d?await trajectoryVersion(p,d.survey_version_id,currentDetail?.quality_status==='READY'&&d.quality_status==='READY'?currentDetail.survey_version_id:undefined):null;if(n!==versionSerial||disposed)return;design.value=designDetail;await loadNeighborDetails()}catch(e){if(n===versionSerial&&!disposed)ElMessage.error(e.message||'版本加载失败')}}
  async function loadDetail(){const n=++versionSerial,p=context.fieldId.value,v=versionId.value;if(!p||!v)return;try{const row=await trajectoryVersion(p,v);if(n===versionSerial&&!disposed){detail.value=row;result.value=null;if(design.value)design.value=await trajectoryVersion(p,design.value.survey_version_id,row.quality_status==='READY'&&design.value.quality_status==='READY'?v:undefined);if(n===versionSerial&&!disposed)await loadNeighborDetails()}}catch(e){ElMessage.error(e.message)}}
  async function loadNeighborDetails(){const n=++neighborSerial,p=context.fieldId.value;try{const rows=await Promise.all(selectedNeighbors.value.map(x=>trajectoryVersion(p,x.survey_version_id,versionId.value)));if(n===neighborSerial&&!disposed)neighborDetails.value=rows}catch(e){ElMessage.error('邻井版本加载失败：'+e.message)}}
  async function saveNeighbors(){const invalid=neighbors.value.find(n=>n.version_policy==='PINNED'&&n.included&&!n.survey_version_id);if(invalid)throw Error('固定版本邻井必须选择一个已复核版本');const p=context.fieldId.value;await registry(p,`/wellbores/${boreId.value}/neighbors`,'put',neighbors.value.map(n=>({wellboreId:n.wellbore_id,included:n.included,versionPolicy:n.version_policy,pinnedVersionId:n.version_policy==='PINNED'?n.survey_version_id:null})));const rows=await boreNeighbors(p,boreId.value);neighbors.value=rows.map(r=>({...r,usable:!!r.survey_version_id&&r.quality_status==='READY'&&r.source_review_status==='CONFIRMED'}));await loadNeighborDetails();ElMessage.success('邻井关系已确认并保存')}
  async function save(payload={}){await registry(context.fieldId.value,`/workspace/${task}`,'put',{title:title.value,payload:{wellboreId:boreId.value,surveyVersionId:versionId.value,neighbors:selectedNeighbors.value.map(n=>({wellboreId:n.wellbore_id,versionId:n.survey_version_id})),...payload}});ElMessage.success('工作方案已保存')}
  async function rename(){try{const {value}=await ElMessageBox.prompt('请输入方案名称','重命名',{inputValue:title.value,inputValidator:v=>v.trim().length>0&&v.trim().length<=128||'名称为1～128字'});title.value=value.trim();await save()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e.message)}}
  async function showHistory(){const response=await taskHistory(context.fieldId.value,task);history.value=response?.rows||response?.data?.rows||[];historyOpen.value=true}
  async function restoreHistory(row){
    busy.value=true
    try{
      const p=context.fieldId.value,n=++versionSerial;neighborSerial++
      const stored=await taskHistoryDetail(p,row.record_key),record=stored.record||{},snapshot=parse(stored.snapshot?.input_json)||{}
      const data=parse(record.result_summary_json||record.result_json)
      if(!data||!Object.keys(data).length)throw Error('此任务未保存可展示的结果')
      const v=snapshot.reference_survey_version_id||snapshot.current_survey_version_id||record.reference_survey_version_id||record.current_survey_version_id
      if(!v)throw Error('历史任务缺少输入版本，不能以当前数据替代历史')
      const reference=await trajectoryVersion(p,v)
      const [vs,ns,nds]=await Promise.all([boreVersions(p,reference.wellbore_id,'ACTUAL'),boreNeighbors(p,reference.wellbore_id),Promise.all((snapshot.neighbors||snapshot.obstacle_versions||snapshot.input_versions?.filter(v=>v.role==='NEIGHBOR')||[]).map(x=>trajectoryVersion(p,x.survey_version_id,v)))])
      if(disposed||n!==versionSerial||p!==context.fieldId.value)return
      hydrating=true;boreId.value=reference.wellbore_id;hydrating=false
      versions.value=vs;versionId.value=Number(v);detail.value=reference;design.value=null
      neighbors.value=ns.map(x=>{const exact=nds.find(d=>Number(d.wellbore_id)===Number(x.wellbore_id));return {...x,included:!!exact,survey_version_id:exact?.survey_version_id||x.survey_version_id,version_policy:exact?'PINNED':x.version_policy}})
      neighborDetails.value=nds;historySnapshot.value={...stored,input:snapshot};result.value=data;historyOpen.value=false
      return historySnapshot.value
    }catch(e){ElMessage.error(e.message||'历史任务读取失败')}finally{busy.value=false;hydrating=false}
  }
  async function exportReport(format='bundle',sections){if(!result.value)return;const blob=await exportDrillingReport({field_id:context.fieldId.value,title:title.value,format,payload:{...result.value,report_sections:sections,reference_version:versionId.value,wellbore:bore.value,neighbors:selectedNeighbors.value}});downloadBlob(blob,`${title.value}.${format==='bundle'?'zip':format}`)}
  function referencePayload(){if(!detail.value)throw Error('请选择实钻轨迹版本');return {reference_well_id:detail.value.well_id,reference_survey_version_id:versionId.value,neighbors:selectedNeighbors.value.map(n=>({well_id:n.well_id,survey_version_id:n.survey_version_id}))}}
  watch(context.fieldId,loadBores,{immediate:true});watch(boreId,(value)=>{context.activeWellboreId.value=value||null;if(value)localStorage.setItem('drilling.activeWellboreId',String(value));else localStorage.removeItem('drilling.activeWellboreId');if(!hydrating)loadVersions()},{flush:'sync'})
  onBeforeUnmount(()=>{disposed=true;serial++;versionSerial++;neighborSerial++})
  return {...context,bores,boreId,bore,borePath,wellboreOptions,versions,versionId,detail,design,neighbors,selectedNeighbors,neighborDetails,baseTrajectories,busy,result,title,historyOpen,history,workspaceDraft,historySnapshot,label,selectedVersion,loadBores,loadDetail,loadNeighborDetails,saveNeighbors,save,rename,showHistory,restoreHistory,exportReport,referencePayload}
}
export function input(v){const rows=v.stations||[],p=rows[0]||{};return {stations:rows.map(r=>({md:r.md,inclination:r.inclination,azimuth:r.azimuth})),origin_north:Number(p.north||0),origin_east:Number(p.east||0),origin_tvd:Number(p.tvd||0)}}
