import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessage } from 'element-plus'
import UsersView from '@/views/system/UsersView.vue'
import { systemApi } from '@/api/system'
import { dataScopeLabel, isAssignableDataScope } from '@/utils/dataScope'

// ZT-14：管理员在用户管理里设置账号的数据范围；新账号默认只看本单位。
const auth = vi.hoisted(() => ({
  user: { menu_keys: ['users'], permission_codes: ['users.read', 'users.op', 'users.auth'] },
  hasPermission(code) { return this.user.permission_codes.includes(code) }
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('@/api/organizationDirectory', () => ({ directoryApi: { profile: vi.fn(), profiles: vi.fn(), contacts: vi.fn(), contact: vi.fn(), bindings: vi.fn(), options: vi.fn(), saveProfile: vi.fn() } }))
vi.mock('@/api/system', () => ({ systemApi: { users: vi.fn(), organizations: vi.fn(), roles: vi.fn(), createUser: vi.fn(), updateUser: vi.fn(), updateOrganization: vi.fn() } }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

const orgs = [{ org_id: 'org-a', name: '甲单位', parent_id: null }, { org_id: 'org-a1', name: '甲单位下属队', parent_id: 'org-a' }]
const row = (overrides = {}) => ({ user_id: 'u-1', account: 'duty-a', name: '值班员甲', phone: '', org_id: 'org-a', org_name: '甲单位', role_code: 'ROLE-DUTY', role_name: '值班员', status: 'ACTIVE', data_scope: 'OWN_ORG', version: 4, ...overrides })
let app, host
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(UsersView); app.use(ElementPlus); app.mount(host); await settle() }
const buttons = label => [...document.querySelectorAll('button')].filter(node => node.textContent.trim() === label)
const dialog = () => [...document.querySelectorAll('.el-dialog')].find(node => node.offsetParent !== null || node.style.display !== 'none')
const radio = label => [...dialog().querySelectorAll('.el-radio')].find(node => node.textContent.trim() === label)
const scopeCells = () => [...document.querySelectorAll('.el-table__body tr')].map(tr => [...tr.querySelectorAll('td')].map(td => td.textContent.trim()))

beforeEach(() => {
  vi.clearAllMocks()
  systemApi.organizations.mockResolvedValue(orgs)
  systemApi.roles.mockResolvedValue([{ role_code: 'ROLE-DUTY', name: '值班员', enabled: true }, { role_code: 'ROLE-ADMIN', name: '超级管理员', enabled: true }])
  systemApi.users.mockResolvedValue({ items: [row(), row({ user_id: 'u-2', account: 'leader', data_scope: 'ALL' }), row({ user_id: 'u-3', account: 'legacy', data_scope: 'CUSTOM' }), row({ user_id: 'u-0', account: 'admin1', role_code: 'ROLE-ADMIN', role_name: '超级管理员', data_scope: 'ALL' })], total: 4 })
  systemApi.updateUser.mockResolvedValue(row())
  systemApi.createUser.mockResolvedValue(row())
})
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

// 整页挂载用户管理（含表格和单位树）较重，机器繁忙时放宽单条超时。
describe('账号数据范围', { timeout: 30_000 }, () => {
  it('范围名称：可选三种，早期账号的范围只展示', () => {
    expect(['ALL', 'OWN_ORG', 'OWN_ORG_TREE'].every(isAssignableDataScope)).toBe(true)
    expect(isAssignableDataScope('CUSTOM')).toBe(false)
    expect(dataScopeLabel('OWN_ORG_TREE')).toBe('本单位及下级单位')
    expect(dataScopeLabel('CUSTOM')).toBe('指定单位和区域')
  })

  it('用户列表显示每个账号的数据范围', async () => {
    await mount()
    const rows = scopeCells()
    expect(rows[0]).toContain('本单位')
    expect(rows[1]).toContain('全部单位')
    expect(rows[2]).toContain('指定单位和区域')
  })

  it('新增用户默认只看本单位', async () => {
    await mount()
    buttons('新增用户')[0].click(); await settle()
    expect(radio('本单位').classList.contains('is-checked')).toBe(true)
    expect(dialog().textContent).toContain('只能看到所属单位的数据')
  })

  it('改成本单位及下级单位：提示需重新登录，并只发送改动的范围', async () => {
    await mount()
    buttons('修改')[0].click(); await settle()
    expect(dialog().textContent).not.toContain('需要重新登录')
    radio('本单位及下级单位').querySelector('input').click(); await settle()
    expect(dialog().textContent).toContain('保存后该用户需要重新登录')
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.updateUser).toHaveBeenCalledWith('u-1', expect.objectContaining({ data_scope: 'OWN_ORG_TREE', org_id: 'org-a', expected_version: 4 }))
    expect(ElMessage.success).toHaveBeenCalledWith('用户资料已保存，该用户需要重新登录后按新的数据范围查看。')
  })

  it('早期账号不动范围时原样保留，不发送 data_scope', async () => {
    await mount()
    buttons('修改')[2].click(); await settle()
    expect(dialog().textContent).toContain('早期审批设置')
    expect(dialog().querySelector('.el-radio.is-checked')).toBeNull()
    buttons('保存并立即生效')[0].click(); await settle()
    expect(systemApi.updateUser).toHaveBeenCalledWith('u-3', expect.not.objectContaining({ data_scope: expect.anything() }))
    expect(ElMessage.success).toHaveBeenCalledWith('用户资料已保存。')
  })

  it('超级管理员固定全部单位，不能改', async () => {
    await mount()
    buttons('修改')[3].click(); await settle()
    expect(radio('全部单位').classList.contains('is-checked')).toBe(true)
    expect(radio('本单位').classList.contains('is-disabled')).toBe(true)
    expect(dialog().textContent).toContain('超级管理员固定能看到全部单位的数据')
  })
})
