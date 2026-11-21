export function setNeighborIncluded(row, included) {
  const value = !!included
  // An unavailable saved selection is deliberately returned unchecked. It
  // still needs an explicit exclusion to replace the persisted relationship.
  if (!!row.included === value && !(row.requested_included && !value)) return
  row.included = value
  if (!value) row.requested_included = false
  row.selection_dirty = true
}

export function hasIncompatibleNeighborSelection(rows) {
  return rows.some(row => row.coordinate_compatible === false && row.requested_included)
}

export function isEvaluationNeighbor(row) {
  return !!row.included && !!row.relationship_confirmed && !row.selection_dirty &&
    row.usable !== false && row.coordinate_compatible !== false &&
    !!row.survey_version_id && row.quality_status === 'READY' && row.source_review_status === 'CONFIRMED'
}

export function neighborChanges(rows) {
  return rows.filter(row => row.selection_dirty).map(row => ({
    wellboreId: row.wellbore_id, included: !!row.included,
    versionPolicy: row.version_policy,
    pinnedVersionId: row.version_policy === 'PINNED' ? row.survey_version_id : null
  }))
}
