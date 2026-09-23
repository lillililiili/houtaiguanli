export const organizationTypes = [
  { value: 'REGULATOR', label: '监管单位' }, { value: 'OPERATOR', label: '运行单位' },
  { value: 'SERVICE', label: '服务单位' }, { value: 'OTHER', label: '其他单位' }
]
export const contactRoles = [
  { value: 'PILOT', label: '飞手' }, { value: 'PLAN_LIAISON', label: '计划联络人' },
  { value: 'UNIT_LIAISON', label: '单位联络人' }, { value: 'MAINTENANCE', label: '运维联系人' }
]
export function labelOf(items, value) { return items.find(item => item.value === value)?.label || value || '未填写' }
export function configurationError(error) {
  if (error?.status === 409) return `${error.message || '资料已由其他人更新'}。请重新读取当前记录，核对后再保存；本次未覆盖现有资料。`
  if (!error?.status || error?.code === 'NETWORK_ERROR' || error?.code === 'TIMEOUT') return '保存结果尚未确认。请重新读取当前记录核对结果，再决定是否保存，避免重复提交。'
  return error.message || '保存失败，请检查资料后重试。'
}
export function requiresReload(error) { return error?.status === 409 || !error?.status || error?.code === 'NETWORK_ERROR' || error?.code === 'TIMEOUT' }
export function associationText(value) {
  return ({ LINKED: '已关联', ASSOCIATED: '已关联', UNLINKED: '待关联', PARTIAL: '部分关联', CONFLICT: '关联冲突', PENDING: '待关联' })[value] || value || '待关联'
}
