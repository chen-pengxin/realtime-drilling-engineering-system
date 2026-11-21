import {trajectoryIdentity,wellboresInField,engineeringName} from '@/utils/wellboreHierarchy'
import {computed,ref,watch,onBeforeUnmount} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {useRoute} from 'vue-router'
import {listWellbores,boreVersions,trajectoryVersion,boreNeighbors,registry,downloadBlob,taskHistory,taskHistoryDetail} from '@/api/drillingRegistry'
import {useDrillingContext} from './useDrillingContext'
import {exportDrillingReport} from '@/api/drilling'
import {trajectoryColors} from '@/utils/trajectoryColors'
import {isEvaluationNeighbor,neighborChanges,hasIncompatibleNeighborSelection} from '@/utils/neighborSelection'
import {liveRequestMessage as readRequestMessage} from '@/utils/liveDrillingSelection'

export function useTrajectoryTask(task){
  const context=useDrillingContext(),route=useRoute(),bores=ref([]),boreId=ref(),versions=ref([]),versionId=ref(),detail=ref(),design=ref(),neighbors=ref([]),neighborDetails=ref([]),busy=ref(false),result=ref(),title=ref(task==='anticollision'?'防碰评价':'轨迹再规划'),historyOpen=ref(false),history=ref([])
  const workspaceDraft=ref(null),historySnapshot=ref(null)
  const listLoading=ref(false),versionLoading=ref(false),neighborLoading=ref(false),neighborRelationsLoaded=ref(false),loadError=ref(''),neighborError=ref('')
  const inputsLoading=computed(()=>listLoading.value||versionLoading.value||neighborLoading.value)
  const dataError=computed(()=>loadError.value||neighborError.value||(hasIncompatibleNeighborSelection(neighbors.value)?'已选邻井坐标基准不一致，请调整邻井关系':''))
  const silent={silentError:true},settled=promise=>promise.then(value=>({value}),error=>({error}))
  let serial=0,versionSerial=0,neighborSerial=0,disposed=false,hydrating=false
  const parse=value=>typeof value==='string'?JSON.parse(value):value
  const bore=computed(()=>bores.value.find(b=>b.wellbore_id===boreId.value)),selectedVersion=computed(()=>versions.value.find(v=>v.survey_version_id===versionId.value))
  const humanName=engineeringName
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
  const selectedNeighbors=computed(()=>neighbors.value.filter(isEvaluationNeighbor))
  const baseTrajectories=computed(()=>[
    ...(detail.value?.stations?.length?[{...trajectoryIdentity(bore.value),id:'actual',kind:'ACTUAL',label:'实钻轨迹',versionLabel:`${label(bore.value)} · v${detail.value.version_no}`,points:detail.value.plot_stations||detail.value.stations,color:trajectoryColors.ACTUAL}]:[]),
    ...(design.value?.stations?.length?[{...trajectoryIdentity(bore.value),id:'design',kind:'DESIGN',label:'原始设计方案',versionLabel:`v${design.value.version_no}`,points:design.value.plot_stations||design.value.stations,color:trajectoryColors.DESIGN}]:[]),
    ...neighborDetails.value.map(v=>({...trajectoryIdentity(neighbors.value.find(n=>n.survey_version_id===v.survey_version_id)),id:`bore-${v.wellbore_id}`,kind:'NEIGHBOR',trajectory_kind:v.trajectory_kind||'ACTUAL',label:label(neighbors.value.find(n=>n.survey_version_id===v.survey_version_id)||{}),versionLabel:`v${v.version_no}`,points:v.plot_stations||v.stations,color:trajectoryColors.NEIGHBOR}))
  ])
  async function loadBores(){
    const p=context.fieldId.value,n=++serial;versionSerial++;neighborSerial++
    bores.value=[];versions.value=[];detail.value=null;design.value=null;result.value=null;neighbors.value=[];neighborDetails.value=[];workspaceDraft.value=null;historySnapshot.value=null
    title.value=task==='anticollision'?'防碰评价':'轨迹再规划'
    loadError.value='';neighborError.value='';neighborRelationsLoaded.value=false;versionLoading.value=false;neighborLoading.value=false;listLoading.value=!!p
    if(!p)return
    try{
      const savedRequest=settled(registry(p,`/workspace/${task}`,'get',undefined,undefined,silent))
      const available=await listWellbores(p,silent)
      if(disposed||n!==serial)return
      const rows=wellboresInField(available,p)
      bores.value=rows
      const savedResult=await savedRequest
      if(disposed||n!==serial)return
      listLoading.value=false
      const saved=savedResult.value
      let draft={}
      try{draft=parse(saved?.payload_json)||saved?.payload||{}}catch{ /* An unreadable saved draft cannot replace the current survey. */ }
      const chosen=rows.find(b=>String(b.wellbore_id)===String(route.query.wellboreId||''))||rows.find(b=>String(b.well_id)===String(route.query.wellId||''))||rows.find(b=>Number(b.wellbore_id)===Number(draft.wellboreId))||rows.find(b=>Number(b.wellbore_id)===Number(boreId.value))||rows.find(b=>b.engineering_status==='DRILLING'&&b.actual_version_id)||rows.find(b=>b.actual_version_id)||rows[0]
      // Suppress only the synchronous assignment. Subsequent user selections
      // must load immediately, including while this first request is pending.
      hydrating=true;boreId.value=chosen?.wellbore_id;hydrating=false
      context.activeWellboreId.value=chosen?.wellbore_id||null
      if(chosen?.wellbore_id)localStorage.setItem('drilling.activeWellboreId',String(chosen.wellbore_id))
      title.value=saved?.title||(task==='anticollision'?'防碰评价':'轨迹再规划')
      const alert=route.query.alert==='collision'
      await loadVersions(alert?{surveyVersionId:route.query.referenceVersionId}:chosen?.wellbore_id===draft.wellboreId?draft:null)
      if(!disposed&&n===serial&&chosen?.wellbore_id===boreId.value)workspaceDraft.value=alert?null:draft
    }catch(e){if(n===serial&&!disposed)loadError.value=readRequestMessage(e,'井眼读取失败')}
    finally{if(n===serial&&!disposed)listLoading.value=false}
  }
  async function loadVersions(draft){
    const p=context.fieldId.value,b=boreId.value,n=++versionSerial
    const valid=()=>!disposed&&n===versionSerial&&p===context.fieldId.value&&b===boreId.value
    neighborSerial++;versions.value=[];versionId.value=null;detail.value=null;design.value=null;result.value=null;historySnapshot.value=null;neighbors.value=[];neighborDetails.value=[]
    loadError.value='';neighborError.value='';neighborRelationsLoaded.value=false;versionLoading.value=!!(p&&b);neighborLoading.value=false
    if(!p||!b)return
    const designRequest=settled(boreVersions(p,b,'DESIGN',silent))
    const linksRequest=settled(boreNeighbors(p,b,silent))
    try{
      const vs=await boreVersions(p,b,'ACTUAL',silent)
      if(!valid())return
      versions.value=vs
      const current=vs.find(v=>Number(v.survey_version_id)===Number(draft?.surveyVersionId))||vs.find(v=>Number(v.is_active))||vs[0]
      versionId.value=current?.survey_version_id
      if(current){
        const row=await trajectoryVersion(p,current.survey_version_id,undefined,silent)
        if(!valid())return
        detail.value=row
      }
      versionLoading.value=false
      // Show the measured version before either auxiliary request completes.
      await Promise.all([
        loadCurrentDesign(p,designRequest,valid),
        loadNeighborRelations(linksRequest)
      ])
    }catch(e){if(valid())loadError.value='实钻轨迹读取失败：'+readRequestMessage(e)}
    finally{if(valid())versionLoading.value=false}
  }
  async function loadCurrentDesign(p,request,valid){
    const response=await request
    if(!valid())return
    if(response.error){loadError.value='设计版本读取失败：'+readRequestMessage(response.error);return}
    const selected=response.value.find(v=>Number(v.is_active))||response.value[0]
    await loadDesign(p,selected,valid)
  }
  async function loadDesign(p,selected,valid){
    if(!selected)return
    try{
      const target=detail.value?.quality_status==='READY'&&selected.quality_status==='READY'?versionId.value:undefined
      const row=await trajectoryVersion(p,selected.survey_version_id,target,silent)
      if(valid())design.value=row
    }catch(e){if(valid()){design.value=null;loadError.value='设计轨道读取失败：'+readRequestMessage(e)}}
  }
  async function loadDetail(){
    const n=++versionSerial,p=context.fieldId.value,b=boreId.value,v=versionId.value
    const valid=()=>!disposed&&n===versionSerial&&p===context.fieldId.value&&b===boreId.value&&v===versionId.value
    neighborSerial++;detail.value=null;design.value=null;result.value=null;neighborDetails.value=[];neighbors.value=[];historySnapshot.value=null;loadError.value='';neighborError.value=''
    neighborRelationsLoaded.value=false;versionLoading.value=!!(p&&v);neighborLoading.value=false
    if(!p||!v)return
    const linksRequest=settled(boreNeighbors(p,b,silent))
    const designRequest=settled(boreVersions(p,b,'DESIGN',silent))
    try{
      const row=await trajectoryVersion(p,v,undefined,silent)
      if(!valid())return
      detail.value=row
      versionLoading.value=false
      await Promise.all([loadCurrentDesign(p,designRequest,valid),loadNeighborRelations(linksRequest)])
    }catch(e){if(valid())loadError.value='实钻轨迹读取失败：'+readRequestMessage(e)}
    finally{if(valid())versionLoading.value=false}
  }
  async function readNeighborDetails(p,v,valid){
    const rows=await Promise.all(selectedNeighbors.value.map(x=>settled(trajectoryVersion(p,x.survey_version_id,v,silent))))
    if(!valid())return
    neighborDetails.value=rows.filter(row=>!row.error).map(row=>row.value)
    if(rows.some(row=>row.error))neighborError.value='部分邻井轨迹读取失败，请刷新重试'
  }
  async function loadNeighborRelations(request){
    const n=++neighborSerial,p=context.fieldId.value,b=boreId.value,v=versionId.value
    const valid=()=>!disposed&&n===neighborSerial&&p===context.fieldId.value&&b===boreId.value&&v===versionId.value
    neighborLoading.value=true;neighborRelationsLoaded.value=false;neighborError.value='';neighborDetails.value=[]
    try{
      const response=await request
      if(!valid())return
      if(response.error){neighborError.value='邻井关系读取失败：'+readRequestMessage(response.error);return}
      neighbors.value=response.value.map(row=>({...row,selection_dirty:false}))
      neighborRelationsLoaded.value=true
      await readNeighborDetails(p,v,valid)
    }finally{if(valid())neighborLoading.value=false}
  }
  async function loadNeighborDetails(){
    const n=++neighborSerial,p=context.fieldId.value,b=boreId.value,v=versionId.value
    const valid=()=>!disposed&&n===neighborSerial&&p===context.fieldId.value&&b===boreId.value&&v===versionId.value
    neighborLoading.value=true;neighborDetails.value=[];result.value=null
    // A failed discovery must be retried before an empty selection is usable.
    if(!neighborRelationsLoaded.value){
      return loadNeighborRelations(settled(boreNeighbors(p,b,silent)))
    }
    neighborError.value=''
    try{await readNeighborDetails(p,v,valid)}finally{if(valid())neighborLoading.value=false}
  }
  function inputIssue(){
    if(inputsLoading.value)return '轨迹数据正在加载'
    if(!detail.value)return '请选择实钻轨迹版本'
    if(detail.value.reference?.evaluation_eligibility==='BLOCKED')return detail.value.reference.evaluation_message||'请确认实钻版本的坐标基准'
    if(neighborError.value)return neighborError.value
    if(!neighborRelationsLoaded.value)return '邻井关系尚未读取'
    if(neighbors.value.some(row=>row.selection_dirty))return '请先保存邻井关系'
    if(hasIncompatibleNeighborSelection(neighbors.value))return '已选邻井坐标基准不一致，请调整邻井关系'
    return ''
  }
  async function saveNeighbors(){
    const changes=neighborChanges(neighbors.value)
    if(!changes.length)return
    if(changes.some(n=>n.versionPolicy==='PINNED'&&n.included&&!n.pinnedVersionId))return ElMessage.warning('请选择固定版本')
    const p=context.fieldId.value,b=boreId.value
    try{
      await registry(p,`/wellbores/${b}/neighbors`,'put',changes,undefined,silent)
      const rows=await boreNeighbors(p,b,silent)
      if(disposed||p!==context.fieldId.value||b!==boreId.value)return
      neighbors.value=rows.map(r=>({...r,selection_dirty:false}));result.value=null;neighborError.value='';neighborRelationsLoaded.value=true
      await loadNeighborDetails()
      window.dispatchEvent(new CustomEvent('drilling-neighbors-changed',{detail:{fieldId:p,wellboreId:b}}))
      ElMessage.success('邻井关系已保存')
    }catch(e){if(!disposed&&p===context.fieldId.value&&b===boreId.value)ElMessage.error(readRequestMessage(e,'保存失败'))}
  }
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
      versions.value=vs;versionId.value=Number(v);detail.value=reference;design.value=null;loadError.value='';neighborError.value='';neighborRelationsLoaded.value=true;versionLoading.value=false;neighborLoading.value=false
      neighbors.value=ns.map(x=>{const exact=nds.find(d=>Number(d.wellbore_id)===Number(x.wellbore_id));return {...x,included:!!exact,survey_version_id:exact?.survey_version_id||x.survey_version_id,version_policy:exact?'PINNED':x.version_policy}})
      neighborDetails.value=nds;historySnapshot.value={...stored,input:snapshot};result.value=data;historyOpen.value=false
      return historySnapshot.value
    }catch(e){ElMessage.error(e.message||'历史任务读取失败')}finally{busy.value=false;hydrating=false}
  }
  async function exportReport(format='bundle',sections){if(!result.value)return;const blob=await exportDrillingReport({field_id:context.fieldId.value,title:title.value,format,payload:{...result.value,report_sections:sections,reference_version:versionId.value,wellbore:bore.value,neighbors:selectedNeighbors.value}});downloadBlob(blob,`${title.value}.${format==='bundle'?'zip':format}`)}
  function referencePayload(){const issue=inputIssue();if(issue)throw Error(issue);return {reference_well_id:detail.value.well_id,reference_survey_version_id:versionId.value,neighbors:selectedNeighbors.value.map(n=>({well_id:n.well_id,survey_version_id:n.survey_version_id}))}}
  watch(context.fieldId,loadBores,{immediate:true});watch(boreId,(value)=>{context.activeWellboreId.value=value||null;if(value)localStorage.setItem('drilling.activeWellboreId',String(value));else localStorage.removeItem('drilling.activeWellboreId');if(!hydrating)loadVersions()},{flush:'sync'})
  onBeforeUnmount(()=>{disposed=true;serial++;versionSerial++;neighborSerial++})
  return {...context,bores,boreId,bore,borePath,wellboreOptions,versions,versionId,detail,design,neighbors,selectedNeighbors,neighborDetails,baseTrajectories,busy,result,title,historyOpen,history,workspaceDraft,historySnapshot,label,selectedVersion,inputsLoading,dataError,inputIssue,loadBores,loadDetail,loadNeighborDetails,saveNeighbors,save,rename,showHistory,restoreHistory,exportReport,referencePayload}
}
export function input(v){const rows=v.stations||[],p=rows[0]||{};return {stations:rows.map(r=>({md:r.md,inclination:r.inclination,azimuth:r.azimuth})),origin_north:Number(p.north||0),origin_east:Number(p.east||0),origin_tvd:Number(p.tvd||0)}}
