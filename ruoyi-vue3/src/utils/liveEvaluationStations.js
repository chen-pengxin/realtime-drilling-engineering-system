/**
 * Keep measured survey history authoritative and append a model forecast only
 * when it is a new point beyond the last measured station. Forecast data must
 * never replace, reorder, or shorten the immutable measured version.
 */
export function buildEvaluationStations(measuredStations, predictedPoint) {
  const measured = Array.isArray(measuredStations) ? measuredStations : []
  const last = measured.at(-1)
  if (predictedPoint && last && Number(predictedPoint.md) > Number(last.md) + 1e-9) {
    return [...measured, predictedPoint]
  }
  return measured
}
