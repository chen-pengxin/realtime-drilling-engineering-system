import request from '@/utils/request'
const base = fieldId => `/api/drilling/fields/${fieldId}/models`
export const getIncrementalLearning = fieldId => request({ url: `${base(fieldId)}/incremental-learning` })
export const saveIncrementalLearning = (fieldId, data) => request({ url: `${base(fieldId)}/incremental-learning`, method: 'put', data })
export const registerPredictionModel = (fieldId, data) => request({ url: `${base(fieldId)}/register`, method: 'post', data, timeout: 0 })
export const archivePredictionModel = (fieldId, modelId) => request({ url: `${base(fieldId)}/${modelId}`, method: 'delete' })
export const readPrediction = taskId => request({ url: `/api/drilling/predictions/${taskId}` })
export function decode(value, fallback = {}) { try { return typeof value === 'string' ? JSON.parse(value) : value || fallback } catch { return fallback } }
export function formatNumber(value, precision = 2) { return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) ? Number(value).toFixed(precision) : '—' }
export function downloadBlob(blob, name) { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
export const learningStatus = status => ({ WAITING_FOR_DATA: '等待新增实测数据', QUEUED: '等待模型更新', RUNNING: '增量更新中', SUCCEEDED: '模型更新完成', FAILED: '模型更新失败', STOPPED: '更新已停止', LOST: '更新进程中断' }[status] || '尚未触发')
