import { isBusinessMenu } from '@/config/businessMenus'

export const permissionLevels = [
  { value: 'NONE', label: '无权限' }, { value: 'READ', label: '查看' },
  { value: 'OP', label: '操作' }, { value: 'AUTH', label: '授权' }
]
const labels = {
  dashboard: '数据大屏', sensing: '感知监测', flights: '飞行任务', legality: '合法性判定',
  alarms: '异常告警', punishment: '处置处罚', statistics: '统计分析', evidence: '证据管理',
  devices: '前台设备操作', airspace: '空域与航线', risk: '空间风险'
}
export const permissionName = item => labels[item.permission_code] || item.module_name || item.name || item.permission_code
export const permissionRank = level => Math.max(0, permissionLevels.findIndex(item => item.value === level))
export function makePermissionDraft(catalog = [], assignments = []) {
  const selected = new Map(assignments.map(item => [item.permission_code, item]))
  return catalog.map(item => {
    const grant = selected.get(item.permission_code)
    return { ...item, level: grant?.level || 'NONE', menu_enabled: Boolean(grant?.menu_enabled) }
  })
}
export function permissionPayload(permissions = [], actions = []) {
  return {
    permissions: permissions.map(({ permission_code, level, menu_enabled }) => ({ permission_code, level, menu_enabled: Boolean(menu_enabled) })),
    actions: actions.map(({ permission_code, level }) => ({ permission_code, level }))
  }
}
export function setPermissionLevel(rows, code, level) {
  return rows.map(row => row.permission_code === code
    ? { ...row, level, menu_enabled: isBusinessMenu(row) && level !== 'NONE' } : row)
}
export function invalidPermissionDraft(rows) {
  return rows.filter(row => isBusinessMenu(row) && Boolean(row.menu_enabled) !== (row.level !== 'NONE'))
}
