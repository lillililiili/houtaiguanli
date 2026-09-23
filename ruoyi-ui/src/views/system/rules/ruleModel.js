export const CATEGORIES = [
  { key: 'verify', label: '核实规则', noun: '核实' },
  { key: 'counter', label: '反制规则', noun: '反制' },
  { key: 'dispose', label: '通知处罚规则', noun: '通知处罚' }
]

export const categoryMeta = key => CATEGORIES.find(item => item.key === key) || CATEGORIES[0]
export const enabledCount = group => (group?.rules || []).filter(rule => rule.enabled).length

export function conditionText(rule, catalog = []) {
  const item = catalog.find(entry => entry.code === rule.item_code)
  if (!item) return rule.value || '判定项已不在当前目录中'
  if (item.kind === 'NUMBER') return `${item.label}${item.operator || ''} ${rule.value}${item.unit ? ` ${item.unit}` : ''}`
  if (item.kind === 'SELECT') return `${item.label}：${item.options?.find(option => (option.value ?? option) === rule.value)?.label || rule.value}`
  return item.fixed_value || rule.value
}

export function timeSummary(settings = {}) {
  if (settings.schedule_mode === 'ALL_DAY') return '全天 · 北京时间'
  const overnight = settings.end_time && settings.start_time && settings.end_time < settings.start_time ? '（次日）' : ''
  return `${settings.start_time || '未设置'} 至 ${settings.end_time || '未设置'}${overnight} · 北京时间`
}

export function scopeSummary(settings = {}) {
  if (settings.scope_mode === 'ALL') return '全部监测区域'
  return settings.airspace_names?.length ? settings.airspace_names.join('、') : '未选择空域'
}

export function actionSummary(category) {
  if (category === 'verify') return '记录目标身份核实结论'
  if (category === 'counter') return '执行前仍需独立校验有效授权'
  return '通知处罚部门'
}

export function ruleDraft(rule, catalog = []) {
  const first = catalog[0]
  if (rule) return { name: rule.name || '', item_code: rule.item_code, value: String(rule.value ?? ''), hold_seconds: rule.hold_seconds ?? 0, enabled: Boolean(rule.enabled) }
  return { name: first?.default_name || '', item_code: first?.code || '', value: first?.kind === 'FIXED' ? String(first.fixed_value ?? '') : '', hold_seconds: 0, enabled: true }
}

export function settingsDraft(settings = {}) {
  return {
    scope_mode: settings.scope_mode || 'ALL', airspace_ids: [...(settings.airspace_ids || [])],
    schedule_mode: settings.schedule_mode || 'ALL_DAY', start_time: settings.start_time || '08:00', end_time: settings.end_time || '20:00',
    timezone: 'Asia/Shanghai', insufficient_wait_seconds: settings.insufficient_wait_seconds ?? 15, actions: [...(settings.actions || [])]
  }
}
