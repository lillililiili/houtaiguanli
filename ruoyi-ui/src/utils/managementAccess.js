import { isUnitAdministrator } from './userType'

export const managementModules = [
  { module_key: 'devices', menu_key: 'devices', name: '设备管理', operation: '新增、编辑、启停和删除本单位设备' },
  { module_key: 'monitoring', menu_key: 'monitor', name: '设备实时监测', readOnly: true },
  { module_key: 'maintenance', menu_key: 'maintenance', name: '设备异常处理', operation: '接手、处理和办结本单位异常' },
  { module_key: 'commissioning', menu_key: 'commission', name: '设备接入调测', operation: '创建和执行本单位设备调测' },
  { module_key: 'statistics', menu_key: 'stats', name: '报表管理', operation: '导出本单位报表' }
]
export function managementPermission(user, code) {
  return Boolean((isUnitAdministrator(user) ? user.management_context?.permission_codes : user?.permission_codes)?.includes(code))
}
export function managementMenu(user, key) {
  const mapped = managementModules.find(item => item.menu_key === key)?.module_key || key
  return Boolean((isUnitAdministrator(user) ? user.management_context?.menu_keys : user?.menu_keys)?.includes(isUnitAdministrator(user) ? mapped : key))
}
export function maintenancePermission(user, level) { return `${isUnitAdministrator(user) ? 'maintenance' : 'monitoring'}.${level}` }
export function managementDraft(rows = []) {
  return managementModules.map(module => ({ module_key: module.module_key, level: rows.find(row => row.module_key === module.module_key)?.level || 'NONE' }))
}
