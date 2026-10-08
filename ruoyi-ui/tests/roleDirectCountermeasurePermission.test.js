import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessageBox } from 'element-plus'
import RolesView from '@/views/system/RolesView.vue'
import { systemApi } from '@/api/system.js'

vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))
vi.mock('@/api/system.js', () => ({ systemApi: {
  roles: vi.fn(), role: vi.fn(), permissions: vi.fn(), permissionActions: vi.fn(), updateRolePermissions: vi.fn()
} }))
vi.mock('element-plus', async importOriginal => ({
  ...await importOriginal(),
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))

let app
let host
const customRole = { role_code: 'ROLE-DISPATCH', name: '处置值班员', description: '测试角色', builtin: false, user_count: 1, version: 2, permissions: [], actions: [] }

async function settle() {
  for (let index = 0; index < 8; index += 1) { await Promise.resolve(); await nextTick() }
}

async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(RolesView)
  app.use(ElementPlus)
  app.mount(host)
  await settle()
  const actionTab = [...host.querySelectorAll('[role="tab"]')].find(item => item.textContent.includes('动作权限'))
  actionTab.click()
  await settle()
}

function directRow() {
  return [...host.querySelectorAll('.action-row')].find(item => item.textContent.includes('直接反制（免逐次审批）'))
}

beforeEach(() => {
  vi.clearAllMocks()
  systemApi.permissions.mockResolvedValue([])
  systemApi.roles.mockResolvedValue([customRole])
  systemApi.role.mockResolvedValue(customRole)
  systemApi.permissionActions.mockResolvedValue([{ module_code: 'disposal', module_name: '处置授权', actions: [
    { permission_code: 'disposal:approve', name: '审批反制', level: 'OP' },
    { permission_code: 'disposal:direct', name: '直接反制（免逐次审批）', level: 'OP' }
  ] }])
  systemApi.updateRolePermissions.mockResolvedValue(customRole)
  ElMessageBox.confirm.mockResolvedValue()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  document.body.querySelectorAll('.el-popper-container').forEach(item => item.remove())
})

describe('角色直接反制权限', () => {
  it('保存设备操作等级时不提交锁定动作，也不丢失既有反制权限', async () => {
    const role = { ...customRole, permissions: [{ permission_code: 'devices', route_key: 'devices', level: 'READ', menu_enabled: true }],
      actions: [{ permission_code: 'disposal:execute', level: 'OP' }] }
    systemApi.role.mockResolvedValue(role)
    systemApi.updateRolePermissions.mockResolvedValue(role)
    systemApi.permissionActions.mockResolvedValue([
      { module_code: 'map', actions: [{ permission_code: 'map:activate', name: '激活地图' }] },
      { module_code: 'users', actions: [{ permission_code: 'users:delete', name: '删除用户' }] },
      { module_code: 'disposal', actions: [{ permission_code: 'disposal:execute', name: '执行反制' }, { permission_code: 'disposal:direct', name: '直接反制（免逐次审批）' }] }
    ])
    await mount()
    const menuTab = [...host.querySelectorAll('[role="tab"]')].find(item => item.textContent.includes('菜单权限'))
    menuTab.click()
    await settle()
    const deviceRow = [...host.querySelectorAll('[role="row"]')].find(item => item.textContent.includes('设备管理'))
    deviceRow.querySelector('.el-select').click()
    await settle()
    const dropdown = [...document.body.querySelectorAll('.el-select-dropdown')].find(item => item.textContent.includes('无权限'))
    ;[...dropdown.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === '操作').click()
    await settle()
    ;[...host.querySelectorAll('button')].find(item => item.textContent.includes('保存并立即生效')).click()
    await settle()
    const submitted = systemApi.updateRolePermissions.mock.calls[0][1]
    expect(submitted.permissions).toEqual([{ permission_code: 'devices', level: 'OP', menu_enabled: true }])
    expect(submitted.actions).toEqual([{ permission_code: 'disposal:execute', level: 'OP' }, { permission_code: 'disposal:direct', level: 'NONE' }])
  })

  it('默认无权限并显示风险边界说明', async () => {
    await mount()
    const row = directRow()
    expect(row).toBeTruthy()
    expect(row.textContent).toContain('免逐次审批；仍校验反制范围、时效及设备权限。')
    const save = [...host.querySelectorAll('button')].find(item => item.textContent.includes('保存并立即生效'))
    expect(save.disabled).toBe(true)
  })

  it('只提供无和允许两档，且选择允许不会联动审批权限', async () => {
    await mount()
    directRow().querySelector('.el-select').click()
    await settle()
    const dropdowns = [...document.body.querySelectorAll('.el-select-dropdown')]
    const options = [...dropdowns.at(-1).querySelectorAll('.el-select-dropdown__item')].map(item => item.textContent.trim())
    expect(options).toEqual(expect.arrayContaining(['无', '允许']))
    expect(options).not.toContain('查看')
    const allow = [...dropdowns.at(-1).querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === '允许')
    allow.click()
    await settle()
    const save = [...host.querySelectorAll('button')].find(item => item.textContent.includes('保存并立即生效'))
    save.click()
    await settle()
    expect(systemApi.updateRolePermissions).toHaveBeenCalledWith('ROLE-DISPATCH', expect.objectContaining({ actions: expect.arrayContaining([
      { permission_code: 'disposal:approve', level: 'NONE' },
      { permission_code: 'disposal:direct', level: 'OP' }
    ]) }))
  })
})
