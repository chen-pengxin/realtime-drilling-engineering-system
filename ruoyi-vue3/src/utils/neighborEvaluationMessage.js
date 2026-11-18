export function neighborEvaluationMessage(rows = []) {
  const candidates = rows.filter(row => row.auto_candidate && !row.relationship_confirmed)
  const saved = rows.filter(row => row.relationship_confirmed && Number(row.included))
  if (candidates.length) {
    return `已自动识别 ${candidates.length} 口邻井候选，需在邻井树中确认并保存；没有已选且可用于评价的邻井版本，尚未进行防碰评价。`
  }
  if (saved.length) {
    return '已保存邻井关系中的测斜版本当前不可用；没有已选且可用于评价的邻井版本，尚未进行防碰评价。'
  }
  const excluded = rows.filter(row => row.auto_candidate && row.relationship_confirmed && !Number(row.included))
  if (excluded.length) {
    return `自动识别到 ${excluded.length} 口候选邻井，但当前关系未纳入扫描；没有已选且可用于评价的邻井版本，尚未进行防碰评价。`
  }
  return '全井水平闭包盒未识别到邻井候选；没有已选且可用于评价的邻井版本，尚未进行防碰评价。'
}
