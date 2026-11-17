import request from '@/utils/request'
import { saveAs } from 'file-saver'
export const listOperation = (resource, params) => request({ url: `/api/operations/${resource}`, params })
export const getOperation = (resource, id) => request({ url: `/api/operations/${resource}/${id}` })
export const createOperation = (resource, data) => request({ url: `/api/operations/${resource}`, method: 'post', data })
export const updateOperation = (resource, id, data) => request({ url: `/api/operations/${resource}/${id}`, method: 'put', data })
export const removeOperation = (resource, id) => request({ url: `/api/operations/${resource}/${id}`, method: 'delete' })
export const executeOperation = (resource, data) => request({ url: `/api/operations/${resource}`, method: 'post', data })
export async function exportOperation(resource, params) { const blob = await request({ url: `/api/operations/${resource}/export`, params, responseType: 'blob' }); if (blob.type.includes('json')) throw new Error('导出失败'); saveAs(blob, `${resource}-${Date.now()}.csv`) }
export const getRuntimeSettings = () => request({ url: '/api/reference/settings' })
export const getReferenceDictionary = type => request({ url: `/api/reference/dictionaries/${type}` })
export const listNotices = params => request({ url: '/api/notices', params })
export const getNotice = id => request({ url: `/api/notices/${id}` })
export const readNotice = id => request({ url: `/api/notices/${id}/read`, method: 'post' })
export const readAllNotices = () => request({ url: '/api/notices/read-all', method: 'post' })
