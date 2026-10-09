import { createRouter, createWebHistory, START_LOCATION } from 'vue-router';
import NProgress from 'nprogress';
import AdminLayout from '@/layout/AdminLayout.vue';
import { canAccessMenu, firstAccessiblePath } from '@/config/navigation.js';
import { useAuthStore } from '@/stores/auth.js';
import { isBackendUser } from '@/utils/userType.js';

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
      { path: 'operations/interfaces', name: 'Interfaces', component: () => import('@/views/operations/InterfacesView.vue'), meta: { title: '接口配置', menuKey: 'interfaces', permission: 'interfaces.read' } },
      { path: 'operations/maps', name: 'Maps', component: () => import('@/views/operations/MapsView.vue'), meta: { title: '地图管理', menuKey: 'maps', permission: 'maps.read' } },
      { path: 'operations/reports', name: 'Reports', component: () => import('@/views/operations/ReportsView.vue'), meta: { title: '报表管理', menuKey: 'stats', permission: 'statistics.read' } },
      { path: 'system/users', name: 'Users', component: () => import('@/views/system/UsersView.vue'), meta: { title: '用户管理', menuKey: 'users', permission: 'users.read' } },
      { path: 'system/organizations', name: 'Organizations', redirect: '/system/users' },
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

/** 回登录页；原来有会话的说明是登录过期，登录页据此提示。 */
function loginRoute(redirect, expired) {
  return { path: '/login', query: expired ? { redirect, expired: '1' } : { redirect } };
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

  // 会话过期、原地重新登录的弹窗还开着：先留在当前页，免得已填内容随页面切换丢失（ZT-29）。
  if (auth.sessionExpired && auth.canReloginInPlace) return false;
  const hadSession = Boolean(auth.token || auth.user);
  await auth.restore();
  // 切换页面时才发现过期、重新登录弹窗在场：同样留在当前页，由弹窗提示重新登录。
  if (auth.sessionExpired && auth.canReloginInPlace) return false;
  if (auth.token && !auth.user && auth.restoreError) return { path: '/service-unavailable', query: { from: to.fullPath } };
  if (!auth.authenticated) return loginRoute(to.fullPath, hadSession);
  if (!isBackendUser(auth.user)) { auth.clear(); return { path: '/login', query: { denied: '1' } }; }
  if (auth.mustChangePassword && to.path !== '/change-password') return '/change-password';
  // 主动改密在个人资料里，旧地址转过去。
  if (!auth.mustChangePassword && to.path === '/change-password') return { path: '/profile', query: { section: 'password' } };
  if (to.name === 'AdminHome') return firstAccessiblePath(auth.user);
  if (to.meta.menuKey && !canAccessMenu(auth.user, to.meta.menuKey)) return { path: '/forbidden', query: { page: to.meta.title } };
  return true;
});

router.afterEach(to => {
  const appTitle = import.meta.env.VITE_APP_TITLE?.trim() || '无人机融合感知与低空安全管理平台';
  document.title = `${to.meta.title || '后台管理'} - ${appTitle}`;
  NProgress.done();
});

router.onError(() => NProgress.done());

// 会话被服务端拒绝（过期、被撤销）。在后台页面里就地弹窗重新登录，页面和已填内容都留着；
// 其他页面（如首次改密）回登录页并说明登录已过期（ZT-29）。
window.addEventListener('admin:unauthorized', event => {
  const auth = useAuthStore();
  if (auth.canReloginInPlace) { auth.markSessionExpired(event.detail); return; }
  const hadSession = Boolean(auth.token || auth.user);
  auth.clear();
  const current = router.currentRoute.value;
  // 打开页面时的首次校验由路由守卫带着目标地址转登录页，这里再跳会把目标地址冲掉。
  if (current === START_LOCATION) return;
  if (current.name !== 'Login') router.replace(loginRoute(current.fullPath, hadSession));
});

window.addEventListener('admin:access-denied', () => {
  useAuthStore().clear();
  router.replace({ path: '/login', query: { denied: '1' } });
});

export { safeRedirect };
export default router;
