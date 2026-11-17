import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { fileDisplayName } from '@/utils/fileNames'

export async function editFileDisplayName(fieldId, row, refresh) {
  try {
    const { value } = await ElMessageBox.prompt(`原始文件名：${row.original_name}。下载仍使用原名，留空恢复默认显示。`, '修改系统名称', {
      inputValue: fileDisplayName(row), inputPlaceholder: '请输入系统内显示的名称', confirmButtonText: '保存', cancelButtonText: '取消',
      inputValidator: value => !value || (value.trim().length <= 255 && !/[\u0000-\u001f\u007f]/.test(value)) || '最多255个字符，不能包含控制字符'
    })
    const ownerFieldId = Number(row.source_field_id || row.field_id || fieldId)
    const saved = await request({ url: `/api/drilling/fields/${ownerFieldId}/files/${row.file_id}/display-name`, method: 'put', data: { displayName: value || '', expectedDisplayName: row.display_name ?? null } })
    row.display_name = saved.display_name
    if (refresh) await refresh()
    ElMessage.success('系统名称已更新')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.msg || error.message || '保存失败')
  }
}
