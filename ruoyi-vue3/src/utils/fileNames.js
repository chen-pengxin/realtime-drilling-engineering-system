// Display labels never flow into upload filenames or original-file downloads.
const hiddenSourceRoots = ['采气二厂煤岩气完井资料+设计（11口）', '采气二厂煤岩气完井资料+设计(11口)', 'Volve数据集', 'well_control_iscwsa']
export function fileDisplayName(row) {
  if (typeof row?.display_name === 'string' && row.display_name.trim()) return row.display_name
  let name = String(row?.file_label || row?.original_name || '工程资料').replaceAll('\\', '/')
  for (const root of hiddenSourceRoots) name = name.replaceAll(root, '')
  return name.split('/').filter(Boolean).pop()?.trim() || '工程资料'
}
