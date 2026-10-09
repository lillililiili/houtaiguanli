// 业务前台的模块与路由契约；后台导航不能作为角色菜单配置的来源。
// 工作台固定可见，airspace/risk 是 flights 内的页面，不单列菜单。
const businessMenuRoutes = {
  dashboard: 'bigscreen',
  sensing: 'situation',
  flights: 'flights',
  legality: 'legality',
  alarms: 'alarms',
  punishment: 'punish',
  statistics: 'stats',
  evidence: 'evidence'
}

export function isBusinessMenu(permission) {
  return Object.hasOwn(businessMenuRoutes, permission.permission_code)
    && businessMenuRoutes[permission.permission_code] === permission.route_key
}
