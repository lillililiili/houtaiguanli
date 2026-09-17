export const organizationTypes = [
  { value: 'REGULATOR', label: '监管单位' }, { value: 'OPERATOR', label: '运行单位' },
  { value: 'SERVICE', label: '服务单位' }, { value: 'OTHER', label: '其他单位' }
]
export const contactRoles = [
  { value: 'PILOT', label: '飞手' }, { value: 'PLAN_LIAISON', label: '计划联络人' },
  { value: 'UNIT_LIAISON', label: '单位联络人' }, { value: 'MAINTENANCE', label: '运维联系人' }
]
export const notificationPurposes = [
  { value: 'RISK_NOTICE', label: '通知上级', singleton: true },
  { value: 'PLAN_FEEDBACK', label: '计划检查结果反馈' },
  { value: 'ADVISORY_SMS', label: '飞手提醒与劝离短信', singleton: true },
  { value: 'ADVISORY_VOICE', label: '飞手语音提醒与劝离', singleton: true },
  { value: 'UAV_PUNISHMENT', label: '处罚材料移送' },
  { value: 'DEVICE_MAINTENANCE', label: '设备异常运维待办' }
]
export const channelTypes = [
  { value: 'NONE', label: '未配置通道' }, { value: 'MOCK', label: '本地模拟通道' },
  { value: 'API', label: '业务接口（待接通）' }, { value: 'SMS', label: '短信（待接通）' },
  { value: 'VOICE', label: '语音（待接通）' }
]
export function labelOf(items, value) { return items.find(item => item.value === value)?.label || value || '未填写' }
export function fixedRecipient(purpose) {
  if (purpose === 'RISK_NOTICE') return '上级'
  if (purpose === 'ADVISORY_SMS' || purpose === 'ADVISORY_VOICE') return '当前目标所关联计划的已核实飞手'
  return ''
}
export function notificationBody(form, version) {
  const body = {
    purpose: form.purpose, channel_type: form.channel_type, endpoint_ref: (form.endpoint_ref || '').trim(),
    enabled: Boolean(form.enabled), valid_until: form.valid_until ? Number(form.valid_until) : null
  }
  if (!fixedRecipient(form.purpose)) {
    body.contact_id = form.contact_id || null
    if (form.purpose === 'PLAN_FEEDBACK') body.source_binding_id = form.source_binding_id || null
    else body.recipient_org_id = form.recipient_org_id || null
  }
  if (version !== undefined && version !== null) body.expected_version = version
  return body
}
export function configurationError(error) {
  if (error?.status === 409) return `${error.message || '资料已由其他人更新'}。请重新读取当前记录，核对后再保存；本次未覆盖现有资料。`
  if (!error?.status || error?.code === 'NETWORK_ERROR' || error?.code === 'TIMEOUT') return '保存结果尚未确认。请重新读取当前记录核对结果，再决定是否保存，避免重复提交。'
  return error.message || '保存失败，请检查资料后重试。'
}
export function requiresReload(error) { return error?.status === 409 || !error?.status || error?.code === 'NETWORK_ERROR' || error?.code === 'TIMEOUT' }
export function associationText(value) {
  return ({ LINKED: '已关联', ASSOCIATED: '已关联', UNLINKED: '待关联', PARTIAL: '部分关联', CONFLICT: '关联冲突', PENDING: '待关联' })[value] || value || '待关联'
}
