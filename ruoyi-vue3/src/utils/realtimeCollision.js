import { testModelInference } from '@/api/drilling'

const RISK_ORDER = { BLUE: 0, YELLOW: 1, ORANGE: 2, RED: 3 }
let alarmAudioContext

export async function enableCollisionAlarmSound() {
  const AudioContext = window.AudioContext || window.webkitAudioContext
  if (!AudioContext) return false
  alarmAudioContext ||= new AudioContext()
  if (alarmAudioContext.state === 'suspended') await alarmAudioContext.resume()
  return alarmAudioContext.state === 'running'
}

function stationsByMd(stations = []) {
  return [...stations]
    .filter(station => Number.isFinite(Number(station?.md)))
    .sort((left, right) => Number(left.md) - Number(right.md))
}

/** Use the latest measured attitude for model inference or geometric fallback. */
export function extrapolateBit(stations = [], step = 9) {
  const orderedStations = stationsByMd(stations)
  const latest = orderedStations.at(-1)
  if (!latest || !Number.isFinite(Number(latest.md))) return null
  const distance = Math.max(Number(step) || 9, 0.1)
  const inclination = Number(latest.inclination ?? latest.inc ?? 0)
  const azimuth = Number(latest.azimuth ?? latest.azi ?? 0)
  const inc = inclination * Math.PI / 180
  const azi = azimuth * Math.PI / 180
  return {
    md: Number(latest.md) + distance,
    inclination,
    azimuth,
    north: Number(latest.north || 0) + distance * Math.sin(inc) * Math.cos(azi),
    east: Number(latest.east || 0) + distance * Math.sin(inc) * Math.sin(azi),
    tvd: Number(latest.tvd || 0) + distance * Math.cos(inc),
    source: 'PREDICTED_ATTITUDE_EXTRAPOLATION', predicted: true,
    step: distance
  }
}

/** Normalize a trained-model response to the station contract used by scans. */
export function normalizePredictedBit(response, latest, step = 9) {
  const source = response?.result?.predicted_pose || response?.result?.prediction || response?.result?.predicted_station || response?.result?.predicted_position || response?.predicted_pose || response?.prediction || response?.predicted_station || response?.predicted_position || response?.result
  if (!source || !latest) return null
  const point = source.station || source.position || source
  const values = { md: Number(point.md ?? Number(latest.md) + Number(step)), inclination: Number(point.inclination ?? point.inc), azimuth: Number(point.azimuth ?? point.azi) }
  if (![values.md, values.inclination, values.azimuth].every(Number.isFinite)) return null
  let { north, east, tvd } = point
  const hasPosition = [north, east, tvd].every(value => value !== null && value !== undefined && Number.isFinite(Number(value)))
  if (!hasPosition) {
    const length = values.md - Number(latest.md)
    if (length <= 0) return null
    const toVector = (incDeg, aziDeg) => {
      const inc = Number(incDeg) * Math.PI / 180
      const azi = Number(aziDeg) * Math.PI / 180
      return [Math.sin(inc) * Math.cos(azi), Math.sin(inc) * Math.sin(azi), Math.cos(inc)]
    }
    const start = toVector(latest.inclination ?? latest.inc, latest.azimuth ?? latest.azi)
    const end = toVector(values.inclination, values.azimuth)
    const dot = Math.max(-1, Math.min(1, start.reduce((sum, value, index) => sum + value * end[index], 0)))
    const dogleg = Math.acos(dot)
    const ratio = dogleg < 1e-10 ? 1 : 2 / dogleg * Math.tan(dogleg / 2)
    const delta = start.map((value, index) => length * 0.5 * (value + end[index]) * ratio)
    north = Number(latest.north || 0) + delta[0]
    east = Number(latest.east || 0) + delta[1]
    tvd = Number(latest.tvd || 0) + delta[2]
  }
  if (![north, east, tvd].every(value => Number.isFinite(Number(value)))) return null
  return { ...values, north: Number(north), east: Number(east), tvd: Number(tvd), source: 'PREDICTED_TRAINED_MODEL', predicted: true, model_version: response?.model_version || response?.result?.model_version || null }
}

/** Use a configured trained model when available, with the field fallback. */
export async function predictBitPosition({ fieldId, predictionConfig = {}, stations = [] }) {
  const orderedStations = stationsByMd(stations)
  const latest = orderedStations.at(-1)
  const step = Number(predictionConfig.predictionStep || 9)
  const hasModel = predictionConfig.modelVersionId && predictionConfig.templateRuntimePath
  let modelError = null
  if (hasModel && latest) {
    try {
      const response = await testModelInference(fieldId, predictionConfig.modelVersionId, {
        script: predictionConfig.templateRuntimePath,
        device: predictionConfig.device || 'auto',
        timeout_seconds: Number(predictionConfig.timeoutSeconds || 30),
        input: {
          contract: 'drilling.pose-extrapolation.v1',
          prediction_step: step,
          stations: orderedStations.map(({ md, inclination, azimuth, north, east, tvd }) => ({ md, inclination, azimuth, north, east, tvd })),
          current_pose: latest
        }
      })
      const predicted = normalizePredictedBit(response, latest, step)
      if (predicted) return { point: predicted, source: 'TRAINED_MODEL', modelVersionId: predictionConfig.modelVersionId }
      modelError = new Error('预测模型结果未包含有效的 predicted_pose')
    } catch (error) {
      modelError = error
    }
  }
  if (predictionConfig.allowFallback === false) return {point:null,source:hasModel?'MODEL_ERROR':'MODEL_NOT_CONFIGURED',modelError:modelError?.message||null}
  const fallback = extrapolateBit(stations, step)
  return { point: fallback ? { ...fallback, source: hasModel ? 'TANGENT_EXTRAPOLATION_FALLBACK' : 'TANGENT_EXTRAPOLATION' } : null, source: hasModel ? 'TANGENT_EXTRAPOLATION_FALLBACK' : 'TANGENT_EXTRAPOLATION', modelVersionId: predictionConfig.modelVersionId || null, modelError: modelError?.message || null }
}

/** Find the highest risk row in the predicted MD interval. */
export function predictedNetworkRisk(network, currentMd) {
  if (!network || !Number.isFinite(Number(currentMd))) return null
  const rows = []
  for (const pair of network.results || []) {
    const future = (pair.profile || []).filter(row => Number(row.md) > Number(currentMd) + 1e-6)
    if (!future.length) continue
    const selected = future.reduce((best, row) => {
      const bestLevel = String(best?.operating_risk_level || best?.risk_level || 'BLUE').toUpperCase()
      const level = String(row.operating_risk_level || row.risk_level || 'BLUE').toUpperCase()
      if (!best || RISK_ORDER[level] > RISK_ORDER[bestLevel]) return row
      if (RISK_ORDER[level] === RISK_ORDER[bestLevel] && Number(row.operating_sf ?? row.sf ?? Infinity) < Number(best.operating_sf ?? best.sf ?? Infinity)) return row
      return best
    }, null)
    const level = String(selected?.operating_risk_level || selected?.risk_level || 'BLUE').toUpperCase()
    rows.push({
      level,
      rank: RISK_ORDER[level] ?? 0,
      neighborWellId: pair.neighbor_well_id,
      predictedMd: Number(selected.md),
      distance: Number(selected.center_distance ?? selected.min_distance),
      sf: Number(selected.operating_sf ?? selected.sf),
      referencePoint: selected.reference_point,
      neighborPoint: selected.neighbor_point,
      pair
    })
  }
  return rows.sort((a, b) => b.rank - a.rank || (Number.isFinite(a.sf) ? a.sf : Infinity) - (Number.isFinite(b.sf) ? b.sf : Infinity))[0] || null
}

/** Play a short repeating alarm without requiring an audio asset. */
export function playCollisionAlarm() {
  try {
    if (navigator.vibrate) navigator.vibrate([220, 100, 220, 100, 220])
    const context = alarmAudioContext
    if (!context || context.state !== 'running') return
    const start = context.currentTime
    ;[0, 0.24, 0.48].forEach(offset => {
      const oscillator = context.createOscillator()
      const gain = context.createGain()
      oscillator.type = 'square'
      oscillator.frequency.value = 880
      gain.gain.setValueAtTime(0.0001, start + offset)
      gain.gain.exponentialRampToValueAtTime(0.18, start + offset + 0.015)
      gain.gain.exponentialRampToValueAtTime(0.0001, start + offset + 0.16)
      oscillator.connect(gain).connect(context.destination)
      oscillator.start(start + offset)
      oscillator.stop(start + offset + 0.17)
    })
  } catch (_) {
    // Browser autoplay policies may block Web Audio; the modal alert remains
    // the authoritative warning and the next user gesture can enable sound.
  }
}

export function riskLabel(level) {
  return ({ RED: '红色', ORANGE: '橙色', YELLOW: '黄色', BLUE: '蓝色' }[String(level || 'BLUE').toUpperCase()] || level || '蓝色')
}
