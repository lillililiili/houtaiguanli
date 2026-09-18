import { createRouter, createWebHistory } from 'vue-router';
import NProgress from 'nprogress';
import AdminLayout from '@/layout/AdminLayout.vue';
import { canAccessMenu, firstAccessiblePath } from '@/config/navigation.js';
import { useAuthStore } from '@/stores/auth.js';

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true, title: '登录' } },
  { path: '/change-password', name: 'ChangePassword', component: () => import('@/views/auth/ChangePasswordView.vue'), meta: { title: '修改密码' } },
  { path: '/service-unavailable', name: 'ServiceUnavailable', component: () => import('@/views/errors/ServiceUnavailableView.vue'), meta: { public: true, title: '服务暂不可用' } },
  {
    path: '/', component: AdminLayout,
    children: [
      { path: '', name: 'AdminHome', component: () => import('@/views/errors/NoPermissionView.vue'), meta: { title: '后台首页', affix: true } },
      { path: 'no-permission', name: 'NoPermission', component: () => import('@/views/errors/NoPermissionView.vue'), meta: { title: '暂无后台权限' } },
      { path: 'forbidden', name: 'Forbidden', component: () => import('@/views/errors/ForbiddenView.vue'), meta: { title: '无权访问' } },
      { path: 'profile', name: 'Profile', component: () => import('@/views/profile/ProfileView.vue'), meta: { title: '个人资料' } },
      { path: 'operations/devices', name: 'Devices', component: () => import('@/views/operations/DevicesView.vue'), meta: { title: '设备管理', menuKey: 'devices', permission: 'devices.read' } },
      { path: 'operations/monitor', name: 'Monitor', component: () => import('@/views/operations/MonitorView.vue'), meta: { title: '设备实时监测', menuKey: 'monitor', permission: 'monitoring.read' } },
      { path: 'operations/commission', name: 'Commission', component: () => import('@/views/operations/CommissionView.vue'), meta: { title: '设备接入调测', menuKey: 'commission', permission: 'commissioning.read' } },
      { path: 'operations/maps', name: 'Maps', component: () => import('@/views/operations/MapsView.vue'), meta: { title: '地图管理', menuKey: 'maps', permission: 'maps.read' } },
      { path: 'operations/reports', name: 'Reports', component: () => import('@/views/operations/ReportsView.vue'), meta: { title: '报表管理', menuKey: 'stats', permission: 'statistics.read' } },
      { path: 'system/users', name: 'Users', component: () => import('@/views/system/UsersView.vue'), meta: { title: '用户管理', menuKey: 'users', permission: 'users.read' } },
      { path: 'system/organizations', name: 'Organizations', redirect: '/system/users' },
      { path: 'system/notification-settings', name: 'NotificationSettings', component: () => import('@/views/system/NotificationSettingsView.vue'), meta: { title: '通知对象配置', menuKey: 'notificationSettings', permission: 'notificationSettings.read' } },
      { path: 'system/response-plans', name: 'ResponsePlans', component: () => import('@/views/system/rules/RuleManagementView.vue'), meta: { title: '规则管理', menuKey: 'responsePlans', permission: 'responsePlans.read' } },
      { path: 'system/response-plans/legacy', name: 'LegacyResponsePlans', component: () => import('@/views/system/ResponsePlansView.vue'), meta: { title: '原处置预案', menuKey: 'responsePlans', permission: 'responsePlans.read' } },
      { path: 'system/roles', name: 'Roles', component: () => import('@/views/system/RolesView.vue'), meta: { title: '角色管理', menuKey: 'roles', permission: 'roles.read' } },
      { path: 'system/audit', name: 'Audit', component: () => import('@/views/system/AuditView.vue'), meta: { title: '审计日志', menuKey: 'archive', permission: 'audit.read' } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/errors/NotFoundView.vue'), meta: { public: true, title: '页面不存在' } }
];

const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes });

function safeRedirect(value, user) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return firstAccessiblePath(user);
  const resolved = router.resolve(value);
  if (!resolved.matched.length || resolved.meta.public || (resolved.meta.menuKey && !canAccessMenu(user, resolved.meta.menuKey))) return firstAccessiblePath(user);
  return value;
}

router.beforeEach(async to => {
  NProgress.start();
  const auth = useAuthStore();
  if (to.meta.public) {
    if (to.name === 'Login' && auth.token) {
      await auth.restore();
      if (auth.authenticated) return auth.mustChangePassword ? '/change-password' : firstAccessiblePath(auth.user);
    }
    return true;
  }

  await auth.restore();
  if (auth.token && !auth.user && auth.restoreError) return { path: '/service-unavailable', query: { from: to.fullPath } };
  if (!auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } };
  if (auth.mustChangePassword && to.path !== '/change-password') return '/change-password';
  if (!auth.mustChangePassword && to.path === '/change-password') return firstAccessiblePath(auth.user);
  if (to.name === 'AdminHome') return firstAccessiblePath(auth.user);
  if (to.meta.menuKey && !canAccessMenu(auth.user, to.meta.menuKey)) return { path: '/forbidden', query: { page: to.meta.title } };
  return true;
});

router.afterEach(to => {
  document.title = `${to.meta.title || '后台管理'} - ${import.meta.env.VITE_APP_TITLE}`;
  NProgress.done();
});

router.onError(() => NProgress.done());

window.addEventListener('admin:unauthorized', () => {
  const auth = useAuthStore();
  auth.clear();
  if (router.currentRoute.value.name !== 'Login') router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } });
});

export { safeRedirect };
export default router;
