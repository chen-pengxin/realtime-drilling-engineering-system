// Directory operations use the selected object's ownership, independently of
// the wellbore currently being evaluated on the drilling task pages.
export const catalogTypeLabels = {
  BLOCK: '区块', FIELD: '井场', WELL: '井', WELLBORE: '井眼',
  PLAN: '设计方案', SURVEY: '实钻测斜资料', INBOX: '井场资料'
}

export function catalogNodeWritable(node) {
  return !!node && (!node.status || node.status === 'ACTIVE') && !node.properties?.reference_only
}

export function catalogFileTargetAllowed(node) {
  return catalogNodeWritable(node) && ['BLOCK', 'FIELD', 'INBOX', 'WELL', 'WELLBORE', 'PLAN', 'SURVEY'].includes(node.node_type)
}

export function catalogAccess(node, permissions = []) {
  const has = permission => permissions.includes('*:*:*') || permissions.includes(permission)
  const readFiles = has('drilling:file:list'), readData = has('drilling:well:list')
  const writeData = catalogNodeWritable(node) && has('drilling:well:survey:import') && readData
  return {
    readFiles, readData,
    upload: catalogFileTargetAllowed(node) && readFiles && has('drilling:well:survey:import'),
    attach: catalogFileTargetAllowed(node) && readFiles && has('drilling:file:add'),
    writeData,
    renameFile: catalogFileTargetAllowed(node) && readFiles && has('drilling:file:upload')
  }
}

export function catalogUploadError(node, role, kind) {
  if (!catalogFileTargetAllowed(node)) return '请选择可编辑的区块、井场、井、井眼或资料目录'
  // Block/field/well imports are staged for parser confirmation; the
  // confirmation form resolves or creates the missing wellbore.
  if (role === 'TRAJECTORY_DATA' && !node.wellbore_id && !['BLOCK','FIELD','INBOX','WELL'].includes(node.node_type)) return '轨迹数据请选择具体井眼'
  if (role === 'TRAJECTORY_DATA' && !kind) return '请选择设计方案或实钻测斜'
  if (kind) {
    if (!['DESIGN', 'ACTUAL'].includes(kind) || (!node.wellbore_id && !['BLOCK','FIELD','INBOX','WELL'].includes(node.node_type))) return '设计或实钻资料请选择具体井眼'
    if (node.node_type === 'PLAN' && kind !== 'DESIGN' || node.node_type === 'SURVEY' && kind !== 'ACTUAL') return '文件用途与所选资料目录不一致'
  }
  return ''
}

export function catalogPath(node, nodes) {
  const byId = new Map(nodes.map(item => [Number(item.node_id), item]))
  const path = [], visited = new Set()
  for (let current = node; current; current = byId.get(Number(current.parent_id))) {
    const id = Number(current.node_id)
    if (visited.has(id)) throw new Error('工程目录层级存在循环，请检查所属关系')
    visited.add(id)
    path.unshift(current)
  }
  return path
}

export function catalogCreationTarget(node, nodes, type) {
  const allowed = { BLOCK: ['FIELD'], FIELD: ['WELL'], WELL: ['WELLBORE'], WELLBORE: ['PLAN', 'SURVEY'] }
  if (!node || !allowed[node.node_type]?.includes(type)) throw new Error('请先选择对应的区块、井场、井或井眼')
  const path = catalogPath(node, nodes)
  const block = path.find(item => item.node_type === 'BLOCK')
  const field = path.find(item => item.node_type === 'FIELD')
  const well = path.find(item => item.node_type === 'WELL')
  if (!block?.block_id || (type !== 'FIELD' && (!field?.field_id || Number(node.field_id) !== Number(field.field_id)))) {
    throw new Error('所属区块或井场不完整，请重新选择工程目录')
  }
  if (['WELLBORE', 'PLAN', 'SURVEY'].includes(type) && !well?.well_id) throw new Error('请先选择所属井')
  return {
    parentId: Number(node.node_id), fieldId: field ? Number(field.field_id) : null,
    blockId: Number(block.block_id), nodeType: type, path: path.map(item => ({ ...item }))
  }
}
