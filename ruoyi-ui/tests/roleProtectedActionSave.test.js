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
const customRole = { role_code: 'ROLE-CUSTOM', name: '自定义值守', description: '测试角色', builtin: false, user_count: 0, version: 1, permissions: [], actions: [] }

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

function row(label) {
  return [...host.querySelectorAll('.action-row')].find(item => item.textContent.includes(label))
}

async function choose(label, option) {
  const select = row(label).querySelector('.el-select')
  select.click()
  await settle()
  // 每个下拉框挂载时各建一个弹层，顺序与页面上的下拉框一致；按位置取这一行自己的弹层。
  const index = [...host.querySelectorAll('.el-select')].indexOf(select)
  const dropdown = document.body.querySelectorAll('.el-select-dropdown')[index]
  ;[...dropdown.querySelectorAll('.el-select-dropdown__item')].find(item => item.textContent.trim() === option).click()
  await settle()
}

beforeEach(() => {
  vi.clearAllMocks()
  systemApi.permissions.mockResolvedValue([])
  systemApi.roles.mockResolvedValue([customRole])
  systemApi.role.mockResolvedValue(customRole)
  systemApi.permissionActions.mockResolvedValue([
    { module_code: 'alarm', module_name: '告警事件', actions: [
      { permission_code: 'alarm:read', name: '查看告警', level: 'READ' },
      { permission_code: 'alarm:verify', name: '核实告警', level: 'OP' }
    ] },
    { module_code: 'map', module_name: '地图', actions: [{ permission_code: 'map:activate', name: '启用地图版本', level: 'OP' }] },
    { module_code: 'audit', module_name: '审计', actions: [{ permission_code: 'audit:purge', name: '清理审计日志', level: 'OP' }] }
  ])
  systemApi.updateRolePermissions.mockResolvedValue(customRole)
  ElMessageBox.confirm.mockResolvedValue()
})

afterEach(() => {
  app?.unmount()
  host?.remove()
  document.body.querySelectorAll('.el-popper-container').forEach(item => item.remove())
})

describe('自定义角色保存动作权限（BUG-01）', () => {
  it('后台专属动作不显示且不随整组提交，前台动作照常保存', async () => {
    await mount()
    expect(row('启用地图版本')).toBeUndefined()
    expect(row('清理审计日志')).toBeUndefined()
    await choose('查看告警', '查看')
    await choose('核实告警', '操作')
    const save = [...host.querySelectorAll('button')].find(item => item.textContent.includes('保存并立即生效'))
    expect(save.disabled).toBe(false)
    save.click()
    await settle()
    expect(systemApi.updateRolePermissions).toHaveBeenCalledTimes(1)
    const [code, body] = systemApi.updateRolePermissions.mock.calls[0]
    expect(code).toBe('ROLE-CUSTOM')
    expect(body.actions).toEqual([
      { permission_code: 'alarm:read', level: 'READ' },
      { permission_code: 'alarm:verify', level: 'OP' }
    ])
    expect(body.actions.map(item => item.permission_code)).not.toContain('map:activate')
  })
})
