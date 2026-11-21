export { wellboresInField as liveWellboresForField } from './wellboreHierarchy.js'

export function liveRequestMessage(error, fallback = '实时数据读取失败') {
  const message = error?.response?.data?.msg || error?.message || ''
  const raw = message.replace(/^计算服务拒绝请求[：:]\s*/, '')
  try {
    const value = JSON.parse(raw)
    return typeof value.detail === 'string' ? value.detail : fallback
  } catch {
    return message || fallback
  }
}

export function incompatibleNeighborNotice(rows) {
  const count = rows.filter(row => row.coordinate_compatible === false).length
  return count ? `${count} 个井眼坐标基准不一致，未参与防碰评价` : ''
}
