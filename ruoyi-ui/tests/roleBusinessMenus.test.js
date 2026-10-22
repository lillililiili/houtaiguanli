import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import RolesView from '@/views/system/RolesView.vue'
import { systemApi } from '@/api/system.js'
import { applyMenuEnabled, applyMenuLevel, inconsistentMenus } from '@/views/system/roles/menuPermissionDraft.js'

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))
vi.mock('@/api/system', () => ({ systemApi: {
  roles: vi.fn(), role: vi.fn(), organizations: vi.fn(), permissions: vi.fn(), permissionActions: vi.fn(),
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
function codes(root) { return [...root.querySelectorAll('.permission-matrix__meta')].map(item => item.dataset.permissionCode) }
function button(text, root = document.body) { return [...root.querySelectorAll('button')].find(item => item.textContent.trim() === text) }

beforeEach(() => {
  vi.clearAllMocks()
  systemApi.organizations.mockResolvedValue([])
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
    const row = [...host.querySelectorAll('.permission-matrix__row')].find(item => item.querySelector('[data-permission-code="statistics"]'))
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
    host.querySelector('[aria-label="控制设备"] input[value="OP"]').click()
    await settle()
    button('保存并立即生效', host).click(); await settle()
    expect(systemApi.updateRolePermissions.mock.calls[0][1].permissions).toContainEqual({ permission_code: 'devices', level: 'OP', menu_enabled: false })
  })

  it.each(['READ', 'OP', 'AUTH'])('设备旧 %s 等级分别显示读取和控制，撤销控制保留读取，撤销读取关闭控制', async level => {
    role.permissions.find(item => item.permission_code === 'devices').level = level
    await mount()
    ;[...host.querySelectorAll('[role="tab"]')].find(item => item.textContent.includes('动作权限')).click()
    await settle()
    const read = () => host.querySelector('[aria-label="查看前台设备"] input[value="OP"]')
    const control = () => host.querySelector('[aria-label="控制设备"] input[value="OP"]')
    expect(read().checked).toBe(true)
    expect(control().checked).toBe(level !== 'READ')
    expect(button('保存并立即生效', host).disabled).toBe(true)
    control().click(); await settle()
    host.querySelector('[aria-label="控制设备"] input[value="NONE"]').click(); await settle()
    expect(read().checked).toBe(true)
    expect(control().checked).toBe(false)
    control().click(); await settle()
    host.querySelector('[aria-label="查看前台设备"] input[value="NONE"]').click(); await settle()
    expect(read().checked).toBe(false)
    expect(control().checked).toBe(false)
    button('保存并立即生效', host).click(); await settle()
    expect(systemApi.updateRolePermissions.mock.calls[0][1].permissions).toContainEqual({ permission_code: 'devices', level: 'NONE', menu_enabled: false })
  })

  it('仅提交当前可配置动作，不把后台动作和已下线动作的历史授权带回', async () => {
    role.actions = [
      { permission_code: 'rule:read', level: 'READ' },
      { permission_code: 'rule:manage', level: 'OP' },
      { permission_code: 'airport:manage', level: 'OP' },
      { permission_code: 'punishment:close', level: 'OP' }
    ]
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
    expect(systemApi.updateRolePermissions.mock.calls[0][1].actions).toEqual([{ permission_code: 'rule:read', level: 'OP' }])
  })

  it('角色列表和菜单矩阵不显示内部角色编码和菜单英文名', async () => {
    await mount()
    expect(host.querySelector('.role-list').textContent).not.toContain(role.role_code)
    const matrix = host.querySelector('.permission-matrix').textContent
    Object.keys(businessRoutes).forEach(code => expect(matrix).not.toContain(code))
  })

  it.each([
    ['READ', false],
    ['NONE', true]
  ])('服务端存量的菜单方框与等级不一致（等级 %s、方框 %s）时，保存前提示并不提交', async (level, enabled) => {
    const alarms = role.permissions.find(item => item.permission_code === 'alarms')
    alarms.level = level; alarms.menu_enabled = enabled
    await mount()
    // 改一个其他菜单让页面进入“有改动”状态。
    const row = [...host.querySelectorAll('.permission-matrix__row')].find(item => item.querySelector('[data-permission-code="evidence"]'))
    row.querySelector('input[type="checkbox"]').click(); await settle()
    button('保存并立即生效', host).click(); await settle()
    expect(systemApi.updateRolePermissions).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('方框与权限等级不一致'))
  })

  it('取消勾选菜单时等级一起改回无权限，保存的组合保持一致', async () => {
    const legality = role.permissions.find(item => item.permission_code === 'legality')
    legality.level = 'OP'; legality.menu_enabled = true
    await mount()
    const row = [...host.querySelectorAll('.permission-matrix__row')].find(item => item.querySelector('[data-permission-code="legality"]'))
    row.querySelector('input[type="checkbox"]').click(); await settle()
    button('保存并立即生效', host).click(); await settle()
    expect(systemApi.updateRolePermissions.mock.calls[0][1].permissions).toContainEqual({ permission_code: 'legality', level: 'NONE', menu_enabled: false })
  })

  it('两个“查看设备”分别显示为前台设备与设备资料，不再同名', async () => {
    systemApi.permissionActions.mockResolvedValue([{ module_code: 'device', module_name: '设备', actions: [{ permission_code: 'device:read', name: '查看设备' }] }])
    await mount()
    ;[...host.querySelectorAll('[role="tab"]')].find(item => item.textContent.includes('动作权限')).click()
    await settle()
    const labels = [...host.querySelectorAll('.action-row__label > span')].map(item => item.textContent.trim())
    expect(labels).toContain('查看前台设备')
    expect(labels).toContain('查看设备资料')
    expect(labels).not.toContain('查看设备')
    expect(host.querySelector('[aria-label="查看设备资料"]')).not.toBeNull()
  })
})

describe('菜单方框与权限等级联动', () => {
  it.each(['READ', 'OP', 'AUTH'])('选择等级 %s 自动勾选菜单，改回无权限取消勾选', level => {
    const row = { permission_code: 'flights', level: 'NONE', menu_enabled: false }
    applyMenuLevel(row, level)
    expect(row).toMatchObject({ level, menu_enabled: true })
    applyMenuLevel(row, 'NONE')
    expect(row).toMatchObject({ level: 'NONE', menu_enabled: false })
  })

  it('勾选菜单默认给查看，取消勾选等级改回无权限，已有等级保持', () => {
    const row = { permission_code: 'alarms', level: 'NONE', menu_enabled: false }
    applyMenuEnabled(row, true)
    expect(row).toMatchObject({ level: 'READ', menu_enabled: true })
    row.level = 'OP'
    applyMenuEnabled(row, true)
    expect(row.level).toBe('OP')
    applyMenuEnabled(row, false)
    expect(row).toMatchObject({ level: 'NONE', menu_enabled: false })
  })

  it('只列出未锁定且方框与等级不一致的菜单', () => {
    const rows = [
      { permission_code: 'a', level: 'READ', menu_enabled: false },
      { permission_code: 'b', level: 'NONE', menu_enabled: true },
      { permission_code: 'c', level: 'OP', menu_enabled: true },
      { permission_code: 'd', level: 'NONE', menu_enabled: false },
      { permission_code: 'e', level: 'READ', menu_enabled: false }
    ]
    expect(inconsistentMenus(rows, row => row.permission_code === 'e').map(row => row.permission_code)).toEqual(['a', 'b'])
  })
})
