import request from '@/utils/request'

export function listDrillingFields() { return request({ url: '/api/drilling/fields', method: 'get' }) }
export function getDrillingField(fieldId) { return request({ url: `/api/drilling/fields/${fieldId}`, method: 'get' }) }
export function listFieldConfigs(fieldId) { return request({ url: `/api/drilling/fields/${fieldId}/configs`, method: 'get' }) }
export function getCurrentFieldConfig(fieldId) { return request({ url: `/api/drilling/fields/${fieldId}/configs/current`, method: 'get' }) }
export function createFieldConfig(fieldId, data) { return request({ url: `/api/drilling/fields/${fieldId}/configs`, method: 'post', data }) }
export function activateFieldConfig(fieldId, version) { return request({ url: `/api/drilling/fields/${fieldId}/configs/${version}/activate`, method: 'put' }) }
export function listWells(fieldId) { return request({ url: `/api/drilling/fields/${fieldId}/wells`, method: 'get' }) }
export function createWell(fieldId, data) { return request({ url: `/api/drilling/fields/${fieldId}/wells`, method: 'post', data }) }
export function listSurveyVersions(fieldId, wellId) { return request({ url: `/api/drilling/fields/${fieldId}/wells/${wellId}/surveys`, method: 'get' }) }
export function saveSurveyVersion(fieldId, wellId, data) { return request({ url: `/api/drilling/fields/${fieldId}/wells/${wellId}/surveys`, method: 'post', data }) }
export function getSurveyVersion(fieldId, wellId, versionId) { return request({ url: `/api/drilling/fields/${fieldId}/wells/${wellId}/surveys/${versionId}`, method: 'get' }) }
export function activateSurveyVersion(fieldId, wellId, versionId) { return request({ url: `/api/drilling/fields/${fieldId}/wells/${wellId}/surveys/${versionId}/activate`, method: 'put' }) }
export function listDrillingBlocks() { return request({ url: '/api/drilling/blocks', method: 'get' }) }
export function createDrillingBlock(data) { return request({ url: '/api/drilling/blocks', method: 'post', data }) }
export function updateDrillingBlock(blockId, data) { return request({ url: `/api/drilling/blocks/${blockId}`, method: 'put', data }) }
export function archiveDrillingBlock(blockId) { return request({ url: `/api/drilling/blocks/${blockId}`, method: 'delete' }) }
export function createDrillingField(data) { return request({ url: '/api/drilling/fields', method: 'post', data }) }
export function updateDrillingField(fieldId, data) { return request({ url: `/api/drilling/fields/${fieldId}`, method: 'put', data }) }
export function archiveDrillingField(fieldId) { return request({ url: `/api/drilling/fields/${fieldId}`, method: 'delete' }) }
export function listFieldFiles(fieldId, options = {}) { return request({ url: `/api/drilling/fields/${fieldId}/files`, method: 'get', params: options }) }
export function uploadFieldFile(fieldId, formData) { return request({ url: `/api/drilling/fields/${fieldId}/files`, method: 'post', data: formData, headers: { 'Content-Type': undefined }, timeout: 0 }) }
export function downloadFieldFile(fieldId, fileId) { return request({ url: `/api/drilling/fields/${fieldId}/files/${fileId}/download`, method: 'get', responseType: 'blob', timeout: 0 }) }
export function deleteFieldFile(fieldId, fileId) { return request({ url: `/api/drilling/fields/${fieldId}/files/${fileId}`, method: 'delete' }) }
export function listFieldAudit(fieldId, limit = 200) { return request({ url: `/api/drilling/fields/${fieldId}/audit`, method: 'get', params: { limit } }) }
export function listTrainingDatasets(fieldId) { return request({ url: `/api/drilling/fields/${fieldId}/training/datasets`, method: 'get' }) }
export function createTrainingDataset(fieldId, data) { return request({ url: `/api/drilling/fields/${fieldId}/training/datasets`, method: 'post', data }) }
export function listTrainingTemplates(fieldId) { return request({ url: `/api/drilling/fields/${fieldId}/training/templates`, method: 'get' }) }
export function createTrainingTemplate(fieldId, data) { return request({ url: `/api/drilling/fields/${fieldId}/training/templates`, method: 'post', data }) }
