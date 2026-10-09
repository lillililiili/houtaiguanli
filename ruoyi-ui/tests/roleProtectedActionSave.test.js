import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessageBox } from 'element-plus'
import RolesView from '@/views/system/RolesView.vue'
import { systemApi } from '@/api/system.js'

vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ hasPermission: () => access.editable }) }))
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
const access = vi.hoisted(() => ({ editable: true }))
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
  const radio = [...row(label).querySelectorAll('.el-radio')].find(item => item.textContent.trim() === option)
  radio.querySelector('input').click()
  await settle()
}

beforeEach(() => {
  vi.clearAllMocks()
  access.editable = true
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
  it.each(['superadmin', 'readonly'])('%s 的单选不可编辑', async scenario => {
    if (scenario === 'superadmin') systemApi.role.mockResolvedValue({ ...customRole, role_code: 'ROLE-ADMIN', builtin: true })
    else access.editable = false
    await mount()
    expect([...host.querySelectorAll('.action-grid input[type="radio"]')].every(input => input.disabled)).toBe(true)
    expect(systemApi.updateRolePermissions).not.toHaveBeenCalled()
  })

  it('后台专属动作不显示且不随整组提交，前台动作照常保存', async () => {
    await mount()
    expect(row('启用地图版本')).toBeUndefined()
    expect(row('清理审计日志')).toBeUndefined()
    await choose('查看告警', '允许')
    await choose('核实告警', '允许')
    const save = [...host.querySelectorAll('button')].find(item => item.textContent.includes('保存并立即生效'))
    expect(save.disabled).toBe(false)
    save.click()
    await settle()
    expect(systemApi.updateRolePermissions).toHaveBeenCalledTimes(1)
    const [code, body] = systemApi.updateRolePermissions.mock.calls[0]
    expect(code).toBe('ROLE-CUSTOM')
    expect(body.actions).toEqual([
      { permission_code: 'alarm:read', level: 'OP' },
      { permission_code: 'alarm:verify', level: 'OP' }
    ])
    expect(body.actions.map(item => item.permission_code)).not.toContain('map:activate')
  })

  it.each(['READ', 'OP', 'AUTH'])('旧 %s 授权显示允许，保存其他动作时保留授权，且可撤销', async level => {
    const role = { ...customRole, actions: [{ permission_code: 'alarm:read', level }] }
    systemApi.role.mockResolvedValue(role)
    systemApi.updateRolePermissions.mockImplementation(async (code, body) => ({ ...role, actions: body.actions }))
    await mount()
    expect(row('查看告警').querySelector('input[value="OP"]').checked).toBe(true)
    expect([...row('查看告警').querySelectorAll('.el-radio')].map(item => item.textContent.trim())).toEqual(['无', '允许'])
    const save = [...host.querySelectorAll('button')].find(item => item.textContent.includes('保存并立即生效'))
    expect(save.disabled).toBe(true)
    await choose('核实告警', '允许')
    save.click(); await settle()
    expect(systemApi.updateRolePermissions.mock.calls[0][1].actions).toContainEqual({ permission_code: 'alarm:read', level: 'OP' })
    await choose('查看告警', '无')
    save.click(); await settle()
    expect(systemApi.updateRolePermissions.mock.calls[1][1].actions).toContainEqual({ permission_code: 'alarm:read', level: 'NONE' })
  })
})
