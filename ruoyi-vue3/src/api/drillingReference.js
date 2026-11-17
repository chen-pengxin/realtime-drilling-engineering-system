import request from '@/utils/request'

export const listReferenceMaterials = () => request({ url: '/api/drilling/reference-materials', method: 'get' })
export const downloadReferenceMaterial = id => request({ url: `/api/drilling/reference-materials/${id}/download`, method: 'get', responseType: 'blob' })

