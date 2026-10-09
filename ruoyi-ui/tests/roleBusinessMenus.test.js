import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessageBox } from 'element-plus'
import RolesView from '@/views/system/RolesView.vue'
import { systemApi } from '@/api/system.js'

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))
vi.mock('@/api/system', () => ({ systemApi: {
  roles: vi.fn(), role: vi.fn(), permissions: vi.fn(), permissionActions: vi.fn(),
  updateRolePermissions: vi.fn(), createRole: vi.fn()
} }))
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal(),
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))

const businessRoutes = { dashboard: 'bigscreen', sensing: 'situation', flights: 'flights', legality: 'legality', alarms: 'alarms', punishment: 'punish', statistics: 'stats', evidence: 'evidence' }
const adminRoutes = { devices: 'devices', monitoring: 'monitor', commissioning: 'commission', interfaces: 'interfaces', maps: 'maps', organizations: 'organizations', responsePlans: 'responsePlans', users: 'users', roles: 'roles', audit: 'archive' }
let app, host, role
const assignment = ({ permission_code, level, menu_enabled }) => ({ permission_code, level, menu_enabled })
async function settle() {
  for (let index = 0; index < 10; index += 1) { await Promise.resolve(); await nextTick() }
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(RolesView)
  app.use(ElementPlus)
  app.mount(host)
  await settle()
}
function codes(root) { return [...root.querySelectorAll('.permission-matrix__meta .code-note')].map(item => item.textContent) }
function button(text, root = document.body) { return [...root.querySelectorAll('button')].find(item => item.textContent.trim() === text) }

beforeEach(() => {
  vi.clearAllMocks()
  role = { role_code: `ROLE-CUSTOM-${crypto.randomUUID()}`, name: '业务角色', builtin: false, version: 4, user_count: 0, actions: [],
    permissions: Object.entries({ ...businessRoutes, ...adminRoutes, futureAdmin: 'future-admin', airspace: null }).map(([permission_code, route_key]) => ({
      permission_code, route_key, module_name: '任意目录名称', level: 'NONE', menu_enabled: false
    })) }
  systemApi.roles.mockImplementation(async () => [role])
  systemApi.role.mockImplementation(async () => structuredClone(role))
  systemApi.permissions.mockImplementation(async () => structuredClone(role.permissions))
  systemApi.permissionActions.mockResolvedValue([])
  systemApi.updateRolePermissions.mockImplementation(async () => structuredClone(role))
  systemApi.createRole.mockImplementation(async () => structuredClone(role))
  ElMessageBox.confirm.mockResolvedValue()
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  document.body.innerHTML = ''
})

describe('角色菜单仅配置业务前台', () => {
  it.each(['NONE', 'OP'])('后台权限为 %s 时只显示前台菜单，保存保留隐藏项', async level => {
    role.permissions.filter(item => ['devices', 'monitoring', 'maps'].includes(item.permission_code)).forEach(item => {
      item.level = level; item.menu_enabled = level !== 'NONE'
    })
    const hidden = role.permissions.filter(item => !Object.hasOwn(businessRoutes, item.permission_code)).map(assignment)
    await mount()
    expect(codes(host)).toEqual(Object.keys(businessRoutes))
    // 改名、未知后台路由和无路由权限都不能扩大配置范围；前后台共用 stats 仍保留前台统计入口。
    const row = [...host.querySelectorAll('.permission-matrix__row')].find(item => item.textContent.includes('statistics'))
    row.querySelector('input[type="checkbox"]').click()
    await settle()
    button('保存并立即生效', host).click()
    await settle()
    const [code, body] = systemApi.updateRolePermissions.mock.calls[0]
    expect(code).toBe(role.role_code)
    expect(body.permissions).toHaveLength(role.permissions.length)
    expect(body.permissions.filter(item => !Object.hasOwn(businessRoutes, item.permission_code))).toEqual(hidden)
    expect(body.permissions).toContainEqual({ permission_code: 'statistics', level: 'READ', menu_enabled: true })
  })

  it('新增角色只列前台菜单，完整提交时后台项保持无权限', async () => {
    await mount()
    button('新增角色', host).click()
    await settle()
    const dialog = document.body.querySelector('.el-dialog')
    expect(codes(dialog)).toEqual(Object.keys(businessRoutes))
    const name = dialog.querySelector('.role-fields input')
    name.value = '新业务角色'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    dialog.querySelector('input[type="checkbox"]').click()
    await settle()
    const save = [...dialog.querySelectorAll('button')].find(item => item.textContent.includes('创建'))
    save.click()
    await settle()
    const body = systemApi.createRole.mock.calls[0][0]
    expect(body.permissions).toHaveLength(role.permissions.length)
    expect(body.permissions.filter(item => !Object.hasOwn(businessRoutes, item.permission_code))).toEqual(
      role.permissions.filter(item => !Object.hasOwn(businessRoutes, item.permission_code)).map(assignment)
    )
    expect(body.permissions).toContainEqual({ permission_code: 'dashboard', level: 'READ', menu_enabled: true })
  })

  it('超级管理员同样只展示前台菜单并保持锁定', async () => {
    role.role_code = 'ROLE-ADMIN'; role.builtin = true
    await mount()
    expect(codes(host)).toEqual(Object.keys(businessRoutes))
    expect([...host.querySelectorAll('.permission-matrix input[type="checkbox"]')].every(input => input.disabled)).toBe(true)
    expect(button('保存并立即生效', host).disabled).toBe(true)
  })

  it('前台设备操作在动作页单独授权，不开启后台设备菜单', async () => {
    await mount()
    ;[...host.querySelectorAll('[role="tab"]')].find(item => item.textContent.includes('动作权限')).click()
    await settle()
    const select = host.querySelector('.business-device-permission .el-select')
    select.click(); await settle()
    const dropdown = document.body.querySelectorAll('.el-select-dropdown')[[...host.querySelectorAll('.el-select')].indexOf(select)]
    ;[...dropdown.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === '操作').click()
    await settle()
    button('保存并立即生效', host).click(); await settle()
    expect(systemApi.updateRolePermissions.mock.calls[0][1].permissions).toContainEqual({ permission_code: 'devices', level: 'OP', menu_enabled: false })
  })

  it('后台管理动作不在前台角色中展示，历史值随完整草稿保留', async () => {
    role.actions = [{ permission_code: 'rule:manage', level: 'OP' }]
    systemApi.permissionActions.mockResolvedValue([{ module_code: 'rule', module_name: '规则', actions: [
      { permission_code: 'rule:read', name: '读取规则' }, { permission_code: 'rule:manage', name: '维护后台规则' }
    ] }])
    await mount()
    ;[...host.querySelectorAll('[role="tab"]')].find(item => item.textContent.includes('动作权限')).click()
    await settle()
    expect(host.querySelector('.action-grid').textContent).toContain('读取规则')
    expect(host.querySelector('.action-grid').textContent).not.toContain('维护后台规则')
    host.querySelector('.permission-matrix input[type="checkbox"]').click()
    await settle()
    button('保存并立即生效', host).click(); await settle()
    expect(systemApi.updateRolePermissions.mock.calls[0][1].actions).toContainEqual({ permission_code: 'rule:manage', level: 'OP' })
  })
})
