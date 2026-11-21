import { trajectoryColors } from './trajectoryColors.js'

export const trajectoryRoles = { REFERENCE: '参考井（正钻井）', NEIGHBOR: '邻井' }
export const trajectoryTypes = { ACTUAL: '实钻轨迹', DESIGN: '原始设计', PLANNED: '再规划轨迹', PREDICTED: '预测轨迹' }
export const trajectoryLineTypes = { solid: '实线', dash: '虚线', dot: '点线', dashdot: '点划线' }

export function trajectoryCategory(trajectory = {}) {
  const kind = String(trajectory.kind || '').toUpperCase()
  const role = trajectory.role === 'NEIGHBOR' || kind === 'NEIGHBOR' || /^(neighbor-|bore-)/.test(String(trajectory.id)) ? 'NEIGHBOR' : 'REFERENCE'
  const type = String(trajectory.trajectory_kind || trajectory.trajectoryKind || kind).toUpperCase()
  return { role, type: Object.hasOwn(trajectoryTypes, type) ? type : 'ACTUAL' }
}

export function defaultTrajectoryStyle(role, type) {
  return {
    color: role === 'NEIGHBOR' ? (type === 'ACTUAL' ? trajectoryColors.NEIGHBOR : '#b9a8d0') : trajectoryColors[type] || trajectoryColors.ACTUAL,
    width: 2.2,
    dash: type === 'PREDICTED' || (role === 'NEIGHBOR' && type === 'DESIGN') ? 'dash' : 'solid',
    opacity: 1,
    nodeSize: 5
  }
}

export function emptyTrajectoryStyles() {
  return { version: 1, styles: {}, wellbores: {} }
}

export function normalizeTrajectoryStyle(value = {}, fallback = {}) {
  const result = {}
  const color = value.color ?? fallback.color
  if (/^#[0-9a-f]{6}$/i.test(color)) result.color = color.toLowerCase()
  for (const [key, min, max] of [['width', 0.5, 8], ['opacity', 0.1, 1], ['nodeSize', 2, 12]]) {
    const number = value[key] == null || value[key] === '' ? NaN : Number(value[key])
    if (Number.isFinite(number) && number >= min && number <= max) result[key] = number
    else if (fallback[key] != null) result[key] = fallback[key]
  }
  const dash = value.dash ?? fallback.dash
  if (Object.hasOwn(trajectoryLineTypes, dash)) result.dash = dash
  return result
}

// Only recognized style fields are accepted from browser storage. Trajectory
// versions and temporary plot IDs must never become wellbore preference keys.
export function normalizeTrajectoryStyles(value) {
  const result = emptyTrajectoryStyles()
  if (!value || value.version !== 1) return result
  function collect(source) {
    const target = {}
    for (const role of Object.keys(trajectoryRoles)) {
      for (const type of Object.keys(trajectoryTypes)) {
        const style = normalizeTrajectoryStyle(source?.[role]?.[type])
        if (Object.keys(style).length) (target[role] ||= {})[type] = style
      }
    }
    return target
  }
  result.styles = collect(value.styles)
  for (const [id, overrides] of Object.entries(value.wellbores || {})) {
    if (!/^[1-9]\d*$/.test(id)) continue
    const normalized = collect(overrides)
    if (Object.keys(normalized).length) result.wellbores[id] = normalized
  }
  return result
}

export function resolveTrajectoryStyle(preferences, trajectory = {}, engineering = null) {
  const { role, type } = trajectoryCategory(trajectory)
  return {
    ...defaultTrajectoryStyle(role, type),
    ...engineering?.styles?.[role]?.[type],
    ...engineering?.wellbores?.[trajectory.wellbore_id]?.[role]?.[type],
    ...preferences?.styles?.[role]?.[type],
    ...preferences?.wellbores?.[trajectory.wellbore_id]?.[role]?.[type]
  }
}

export function setTrajectoryStyle(preferences, { role, type, wellboreIds = [], style, reset = false }) {
  const result = normalizeTrajectoryStyles(preferences)
  if (!Object.hasOwn(trajectoryRoles, role) || !Object.hasOwn(trajectoryTypes, type)) return result
  const ids = [...new Set(wellboreIds.map(String))].filter(id => /^[1-9]\d*$/.test(id))
  for (const id of ids.length ? ids : [null]) {
    const target = id ? (result.wellbores[id] ||= {}) : result.styles
    const normalized = normalizeTrajectoryStyle(style)
    if (reset || !Object.keys(normalized).length) {
      if (target[role]) {
        delete target[role][type]
        if (!Object.keys(target[role]).length) delete target[role]
      }
      if (id && !Object.keys(target).length) delete result.wellbores[id]
    } else (target[role] ||= {})[type] = normalized
  }
  return result
}
