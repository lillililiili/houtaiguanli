import { ElMessageBox } from 'element-plus'

// Keep the existing audit contract without presenting generated text as a user-written reason.
export const DELETE_CONFIRMATION_RECORD = '用户在界面确认删除（系统记录，未填写原因）'

export async function confirmDeletion(message, title, options = {}) {
  await ElMessageBox.confirm(message, title, {
    type: 'warning',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
    ...options
  })
  return DELETE_CONFIRMATION_RECORD
}
