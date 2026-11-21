export function neighborEvaluationMessage(rows = []) {
  const saved = rows.filter(row => row.relationship_confirmed && Number(row.included))
  if (saved.length) {
    return '邻井版本不可用'
  }
  return rows.length ? '未选择邻井' : '范围内无邻井'
}
