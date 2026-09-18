import { Cpu, Monitor, Connection, User, UserFilled, DocumentChecked, DataAnalysis, MapLocation, Bell } from '@element-plus/icons-vue';

export const navigationGroups = [
  {
    title: '运维管理', key: 'operations', icon: Cpu,
    children: [
      { title: '设备管理', key: 'devices', path: '/operations/devices', permission: 'devices.read', icon: Cpu },
      { title: '设备实时监测', key: 'monitor', path: '/operations/monitor', permission: 'monitoring.read', icon: Monitor },
      { title: '设备接入调测', key: 'commission', path: '/operations/commission', permission: 'commissioning.read', icon: Connection },
      { title: '地图管理', key: 'maps', path: '/operations/maps', permission: 'maps.read', icon: MapLocation },
      { title: '报表管理', key: 'stats', path: '/operations/reports', permission: 'statistics.read', icon: DataAnalysis }
    ]
  },
  {
    title: '系统管理', key: 'system', icon: UserFilled,
    children: [
      { title: '用户管理', key: 'users', path: '/system/users', permission: 'users.read', icon: User },
      { title: '通知对象配置', key: 'notificationSettings', path: '/system/notification-settings', permission: 'notificationSettings.read', icon: Bell },
      { title: '规则管理', key: 'responsePlans', path: '/system/response-plans', permission: 'responsePlans.read', icon: DocumentChecked },
      { title: '角色管理', key: 'roles', path: '/system/roles', permission: 'roles.read', icon: UserFilled },
      { title: '审计日志', key: 'archive', path: '/system/audit', permission: 'audit.read', icon: DocumentChecked }
    ]
  }
];

export const navigationItems = navigationGroups.flatMap(group => group.children);

export function hasUserAccess(user) {
  return user?.menu_keys?.includes('users') && user?.permission_codes?.includes('users.read') || false;
}
export function hasOrganizationAccess(user) {
  return user?.menu_keys?.includes('organizations') && user?.permission_codes?.includes('organizations.read') || false;
}
export function accessibleItems(user) {
  const keys = new Set(user?.menu_keys || []);
  const permissions = new Set(user?.permission_codes || []);
  return navigationItems.filter(item => item.key === 'users' ? hasUserAccess(user) || hasOrganizationAccess(user) : keys.has(item.key) && permissions.has(item.permission));
}

export function firstAccessiblePath(user) {
  return accessibleItems(user)[0]?.path || '/no-permission';
}

export function canAccessMenu(user, key) {
  return accessibleItems(user).some(item => item.key === key);
}
