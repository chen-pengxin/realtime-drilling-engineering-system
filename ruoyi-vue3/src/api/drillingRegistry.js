import request from '@/utils/request'
const root=p=>`/api/drilling/fields/${p}/registry`
export const registry=(p,path,method='get',data,params)=>request({url:root(p)+path,method,data,params,timeout:path==='/engine-confirmation'?20000:240000})
export const listWellbores=p=>registry(p,'/wellbores')
export const boreVersions=(p,b,kind)=>registry(p,`/wellbores/${b}/versions`,'get',undefined,{kind})
export const trajectoryVersion=(p,v,viewReferenceId)=>registry(p,`/versions/${v}`,'get',undefined,{viewReferenceId,viewStep:10})
export const boreNeighbors=(p,b)=>registry(p,`/wellbores/${b}/neighbors`)
export const activateTrajectoryVersion=(p,v,expectedCurrentVersionId)=>registry(p,`/versions/${v}/activate`,'put',{expectedCurrentVersionId})
export const compareTrajectoryVersions=(p,from,to)=>registry(p,`/versions/${from}/compare`,'get',undefined,{otherId:to})
function sourceUploadData(data,association={}){for(const key of ['nodeId','wellboreId','documentRole','trajectoryKind','documentId','expectedEditionNo','displayName']){const value=association[key];if(value!=null&&value!=='')data.append(key,value)}return data}
export const uploadSurveySource=(p,file,association={})=>{const data=new FormData();data.append('file',file);sourceUploadData(data,association);return request({url:root(p)+'/sources',method:'post',data,headers:{'Content-Type':undefined,repeatSubmit:false},timeout:240000})}
export const sourceExtractions=(p,s)=>registry(p,`/sources/${s}/extractions`)
export const sourceReview=(p,s)=>registry(p,`/sources/${s}/review`)
export const confirmSourceReview=(p,s,data)=>registry(p,`/sources/${s}/review`,'put',data)
export const previewSurveyRevision=(p,e,data)=>registry(p,`/extractions/${e}/preview`,'post',data)
export const commitSurveyRevision=(p,e,data)=>registry(p,`/extractions/${e}/commit`,'post',data)
export const engineCatalog=()=>request({url:'/api/algorithm/engine/catalog'})
export const downloadBlob=(blob,name)=>{const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
export function surveyInput(version){const rows=version?.stations||[];const p=rows[0]||{};return {origin_north:Number(p.north||0),origin_east:Number(p.east||0),origin_tvd:Number(p.tvd||0),stations:rows.map(r=>({md:Number(r.md),inclination:Number(r.inclination),azimuth:Number(r.azimuth)}))}}
export const exportReduction=(p,v)=>request({url:`${root(p)}/versions/${v}/reduction-export`,method:'get',responseType:'blob'})
export const taskHistory=(p,type)=>request({url:'/api/drilling/centers/history',method:'get',params:{fieldId:p,type:type==='anticollision'?'COLLISION':'REPLANNING',pageNum:1,pageSize:50},timeout:120000})
export const taskHistoryDetail=(p,key)=>request({url:`/api/drilling/centers/history/${encodeURIComponent(key)}`,params:{fieldId:p},timeout:120000})
export const downloadSourceOriginal=(p,s)=>request({url:`${root(p)}/sources/${s}/original`,method:'get',responseType:'blob'})
export const exportTrajectorySource=(p,v,mode='PATCHED')=>request({url:`${root(p)}/versions/${v}/source-export`,method:'get',params:{mode},responseType:'blob',timeout:240000})
export const exportTrajectoryPackage=(p,v)=>request({url:`${root(p)}/versions/${v}/export-package`,method:'get',responseType:'blob',timeout:240000})
export const uploadSourceBatch=(p,files,association={})=>{const data=new FormData();files.forEach(f=>data.append('files',f));sourceUploadData(data,association);return request({url:root(p)+'/sources/batch',method:'post',data,headers:{'Content-Type':undefined,repeatSubmit:false},timeout:600000})}
