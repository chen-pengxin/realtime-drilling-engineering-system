import request from '@/utils/request'
const root = fieldId => `/api/drilling/fields/${fieldId}/library`
// Several engineering pages mount together and can ask for the same catalog
// during the first render. Share that request so one slow query cannot create
// a stack of identical timeout notifications.
let catalogTreeRequest = null
export const catalogTree = () => {
  if (catalogTreeRequest) return catalogTreeRequest
  catalogTreeRequest = request({ silentError: true, url: '/api/drilling/library/tree', method: 'get', timeout: 30000 })
    .finally(() => { catalogTreeRequest = null })
  return catalogTreeRequest
}
export const libraryTree = fieldId => request({ silentError: true, url: `${root(fieldId)}/tree`, method: 'get' })
export const libraryNode = (fieldId, nodeId, params = {}) => request({ silentError: true, url: `${root(fieldId)}/nodes/${nodeId}`, method: 'get', params })
export const createLibraryNode = (fieldId, data) => request({ silentError: true, url: `${root(fieldId)}/nodes`, method: 'post', data })
export const updateLibraryNode = (fieldId, nodeId, data) => request({ silentError: true, url: `${root(fieldId)}/nodes/${nodeId}`, method: 'put', data })
export const archiveLibraryNode = (fieldId, nodeId) => request({ silentError: true, url: `${root(fieldId)}/nodes/${nodeId}`, method: 'delete' })
export const libraryDeleteImpact = (fieldId, nodeId) => request({ silentError: true, url: `${root(fieldId)}/nodes/${nodeId}/delete-impact`, method: 'get' })
export const attachLibraryDocument = (fieldId, nodeId, data) => request({ silentError: true, url: `${root(fieldId)}/nodes/${nodeId}/documents`, method: 'post', data })
export const createLibraryRevision = (fieldId, nodeId, data) => request({ silentError: true, url: `${root(fieldId)}/nodes/${nodeId}/revisions`, method: 'post', data })
export const attachRevisionData = (fieldId, revisionId, surveyVersionId, label) => request({ silentError: true, url: `${root(fieldId)}/revisions/${revisionId}/data`, method: 'post', data: { surveyVersionId, label } })
export const activateLibraryRevision = (fieldId, revisionId, data) => request({ silentError: true, url: `${root(fieldId)}/revisions/${revisionId}/activate`, method: 'put', data })
export const libraryRevision = (fieldId, revisionId) => request({ silentError: true, url: `${root(fieldId)}/revisions/${revisionId}`, method: 'get' })
export const libraryDocumentHistory = (fieldId, documentId) => request({ silentError: true, url: `${root(fieldId)}/documents/${documentId}/history`, method: 'get' })
export const updateLibraryFileStatus = (fieldId, documentId, usageState) => request({ silentError: true, url: `${root(fieldId)}/documents/${documentId}/status`, method: 'put', data: { usageState } })
export const libraryDocument = (fieldId, documentId) => request({ silentError: true, url: `${root(fieldId)}/documents/${documentId}`, method: 'get' })
export const listTargets = (fieldId, wellboreId) => request({ silentError: true, url: `${root(fieldId)}/wellbores/${wellboreId}/targets`, method: 'get' })
export const createTarget = (fieldId, wellboreId, data) => request({ silentError: true, url: `${root(fieldId)}/wellbores/${wellboreId}/targets`, method: 'post', data })
export const updateTarget = (fieldId, targetId, data) => request({ silentError: true, url: `${root(fieldId)}/targets/${targetId}`, method: 'put', data })
export const archiveTarget = (fieldId, targetId) => request({ silentError: true, url: `${root(fieldId)}/targets/${targetId}`, method: 'delete' })
export const listTrajectoryTargetPoints = (fieldId, wellboreId, versionId) => request({ silentError: true, url: `${root(fieldId)}/wellbores/${wellboreId}/trajectory-target-points`, method: 'get', params: { versionId } })
export const updateTrajectoryTargetPoint = (fieldId, wellboreId, targetPointId, data) => request({ silentError: true, url: `${root(fieldId)}/wellbores/${wellboreId}/trajectory-target-points/${targetPointId}`, method: 'put', data })
export const archiveTrajectoryTargetPoint = (fieldId, wellboreId, targetPointId, versionId) => request({ silentError: true, url: `${root(fieldId)}/wellbores/${wellboreId}/trajectory-target-points/${targetPointId}`, method: 'delete', params: { versionId } })
export const engineeringTableData=(p,b,versionId)=>request({url:`${root(p)}/wellbores/${b}/engineering-data`,params:{versionId}})
export const saveEngineeringBasic=(p,b,data)=>request({url:`${root(p)}/wellbores/${b}/basic-data`,method:'put',data})
export const engineeringFileImpact=(p,d)=>request({url:`${root(p)}/documents/${d}/impact`})
export const deleteEngineeringDocument=(p,d)=>request({url:`${root(p)}/documents/${d}`,method:'delete'})
export const exportLibraryFiles=(p,nodeId,sourceIds)=>request({silentError:true,url:`${root(p)}/nodes/${nodeId}/files/export`,method:'post',data:{sourceIds},responseType:'blob',timeout:240000})
export const createTrajectoryTargetPoint=(p,b,data)=>request({url:`${root(p)}/wellbores/${b}/trajectory-target-points`,method:'post',data})
