// One canonical engineering hierarchy for selection and plot visibility.
export function wellboresInField(rows = [], fieldId) {
  return rows.filter(row => Number(row.owner_field_id || row.field_id) === Number(fieldId))
}

export function wellboreName(row = {}) {
  const name = row.wellbore_name || row.wellbore_code
  return !name || [row.well_name, row.well_code].includes(name) ? '主井眼' : name
}

export function engineeringName(value) {
  let text = String(value || '').trim()
  // Imported directory prefixes may be removed; a slash inside a real
  // engineering name (15/9区块 or 15/9-19 A) is part of its identity.
  for (const root of ['采气二厂煤岩气完井资料+设计（11口）','采气二厂煤岩气完井资料+设计(11口)','Volve数据集','well_control_iscwsa']) {
    text = text.replaceAll(root, '')
  }
  return text.trim().replace(/^[\\/]+|[\\/]+$/g, '')
}

export function wellborePath(row = {}) {
  return [engineeringName(row.block_name) || '未注明区块', engineeringName(row.field_name) || '未注明井场',
    engineeringName(row.well_name || row.well_code) || '未命名井', wellboreName(row)].join(' / ')
}

export function coordinateReferenceLabel(reference = {}) {
  if (reference.crs) return reference.crs
  return reference.coordinate_system === 'LOCAL_ENU' ? '局部东—北—垂向坐标' : reference.coordinate_system || reference.coordinate_system_text || '—'
}

export function wellboreTree(rows = [], { trajectories = false } = {}) {
  const roots = new Map()
  for (const row of rows) {
    if (String(row.well_code || '').startsWith('__ARCHIVED_ALIAS_')) continue
    const blockKey = `block:${row.block_id || row.block_name || 'unknown'}`
    const fieldKey = `${blockKey}/field:${row.field_id || row.field_name || 'unknown'}`
    const wellKey = `${fieldKey}/well:${row.well_id || row.well_code || 'unknown'}`
    if (!roots.has(blockKey)) roots.set(blockKey, { value: blockKey, label: row.block_name || '未注明区块', children: new Map() })
    const block = roots.get(blockKey)
    if (!block.children.has(fieldKey)) block.children.set(fieldKey, { value: fieldKey, label: row.field_name || '未注明井场', children: new Map() })
    const field = block.children.get(fieldKey)
    if (!field.children.has(wellKey)) field.children.set(wellKey, { value: wellKey, label: row.well_name || row.well_code || '未命名井', children: new Map() })
    const well = field.children.get(wellKey)
    const boreId = Number(row.wellbore_id)
    if (!Number.isFinite(boreId)) continue
    if (trajectories) {
      const key = `bore:${boreId}`
      if (!well.children.has(key)) well.children.set(key, { value: key, label: wellboreName(row), children: new Map() })
      well.children.get(key).children.set(String(row.id), { value: String(row.id), label: [row.treeLabel || ({ACTUAL:'实钻轨迹',DESIGN:'设计轨道',PLANNED:'再规划方案',PREDICTED:'预测轨迹',NEIGHBOR:row.trajectory_kind==='DESIGN'?'邻井设计轨道':'邻井实钻轨迹'}[row.kind]) || row.label, row.versionLabel].filter(Boolean).join(' · ') })
    } else well.children.set(boreId, { value: boreId, label: wellboreName(row), row })
  }
  const expand = nodes => [...nodes.values()].map(node => ({ ...node, ...(node.children ? { children: expand(node.children) } : {}) }))
  return expand(roots)
}

export function trajectoryIdentity(row = {}) {
  return Object.fromEntries(['block_id', 'block_name', 'field_id', 'field_name', 'well_id', 'well_code', 'well_name', 'wellbore_id', 'wellbore_code', 'wellbore_name'].map(key => [key, row[key]]))
}
