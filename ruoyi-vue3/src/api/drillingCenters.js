import request from '@/utils/request'

const base = '/api/drilling/centers'
export const centerList = (module, fieldId, params = {}) => request({ url: `${base}/${module}`, params: { fieldId, ...params } })
export const centerGet = (module, fieldId, key) => request({ url: `${base}/${module}/${encodeURIComponent(key)}`, params: { fieldId } })
export const centerAction = (module, fieldId, action, data = {}) => request({ url: `${base}/${module}${action ? `/${action}` : ''}`, method: 'post', params: { fieldId }, data, timeout: 180000 })
export const centerBlob = (module, fieldId, action, data = {}) => request({ url: `${base}/${module}/${action}`, method: 'post', params: { fieldId }, data, responseType: 'blob', timeout: 180000 })
export const reportSources = (fieldId, params) => request({ url: `${base}/reports/sources`, params: { fieldId, ...params } })
export const connectorFiles = fieldId => request({ url: `${base}/connectors/files`, params: { fieldId } })
export const reportDownload = (fieldId, id) => request({ url: `${base}/reports/${id}/download`, params: { fieldId }, responseType: 'blob' })
export function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
