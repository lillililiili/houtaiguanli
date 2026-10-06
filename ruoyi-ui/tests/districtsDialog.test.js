import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus, { ElMessage } from 'element-plus'
import DistrictsDialog from '@/views/system/DistrictsDialog.vue'
import UsersView from '@/views/system/UsersView.vue'
import { systemApi } from '@/api/system'

const auth = vi.hoisted(() => ({ user: {}, hasPermission(code) { return this.user.permission_codes.includes(code) } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('@/api/organizationDirectory', () => ({ directoryApi: { profiles: vi.fn() } }))
vi.mock('@/api/system', () => ({ systemApi: { users: vi.fn(), organizations: vi.fn(), roles: vi.fn(), districts: vi.fn(), createDistrict: vi.fn(), updateDistrict: vi.fn() } }))
vi.mock('element-plus', async original => ({ ...await original(), ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))

let app, host
const district = { district_id: 'd1', district_code: 'DY-DYQ', name: '东营区', enabled: true, version: 3 }
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
async function mount(component, props = {}) { host = document.createElement('div'); document.body.append(host); app = createApp(component, props); app.use(ElementPlus); app.mount(host); await settle() }
const button = label => [...document.querySelectorAll('button')].find(node => node.textContent.trim() === label)
async function click(label) { const node = button(label); expect(node, label).toBeTruthy(); node.click(); await settle() }
async function fill(label, value) {
  const item = [...document.querySelectorAll('.district-form .el-form-item')].find(node => node.querySelector('label')?.textContent === label)
  const input = item.querySelector('input'); input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
}
beforeEach(() => {
  vi.clearAllMocks()
  auth.user = { menu_keys: ['users'], permission_codes: ['users.read', 'users.op', 'users.auth'] }
  systemApi.districts.mockResolvedValue([district])
  systemApi.createDistrict.mockResolvedValue({ ...district, district_id: 'd2', district_code: 'DY-KLX', name: '垦利区', version: 0 })
  systemApi.updateDistrict.mockResolvedValue({ ...district, name: '东营区（新）', version: 4 })
  systemApi.users.mockResolvedValue({ items: [], total: 0 })
  systemApi.roles.mockResolvedValue([])
  systemApi.organizations.mockResolvedValue([])
})
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

describe('区域管理', () => {
  it('用户管理页头有区域管理入口，打开后才读取区域', async () => {
    await mount(UsersView)
    expect(systemApi.districts).not.toHaveBeenCalled()
    await click('区域管理')
    expect(systemApi.districts).toHaveBeenCalledOnce()
    expect(document.body.textContent).toContain('东营区')
    expect(document.body.textContent).toContain('DY-DYQ')
  })

  it('只有单位目录权限的账号看不到区域管理', async () => {
    auth.user = { menu_keys: ['organizations'], permission_codes: ['organizations.read'] }
    await mount(UsersView)
    expect(button('区域管理')).toBeUndefined()
  })

  it('新增区域提交编码和名称，成功后刷新列表', async () => {
    await mount(DistrictsDialog, { visible: true, canEdit: true })
    await click('新增区域')
    await fill('区域名称', ' 垦利区 '); await fill('区域编码', 'DY-KLX')
    await click('保存')
    expect(systemApi.createDistrict).toHaveBeenCalledWith({ district_code: 'DY-KLX', name: '垦利区' }, expect.stringMatching(/^district-create-/))
    expect(systemApi.districts).toHaveBeenCalledTimes(2)
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('垦利区'))
    expect(document.querySelector('.district-form')).toBeNull()
  })

  it('结果没确认时再次保存沿用同一个幂等键，已提交过则刷新提示', async () => {
    systemApi.createDistrict.mockRejectedValueOnce(Object.assign(new Error('暂时无法连接系统'), { code: 'NETWORK_ERROR', status: 0 }))
      .mockRejectedValueOnce(Object.assign(new Error('该操作已提交'), { code: 'IDEMPOTENCY_REPLAY', status: 409 }))
    await mount(DistrictsDialog, { visible: true, canEdit: true })
    await click('新增区域')
    await fill('区域名称', '垦利区'); await fill('区域编码', 'DY-KLX')
    await click('保存')
    expect(document.body.textContent).toContain('暂时无法连接系统')
    await click('保存')
    const [first, second] = systemApi.createDistrict.mock.calls
    expect(second[1]).toBe(first[1])
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('已经提交过'))
    expect(document.querySelector('.district-form')).toBeNull()
  })

  it('改名只改名称并带上当前版本号，编码不可修改', async () => {
    await mount(DistrictsDialog, { visible: true, canEdit: true })
    await click('改名')
    const code = [...document.querySelectorAll('.district-form .el-form-item')].find(node => node.querySelector('label')?.textContent === '区域编码').querySelector('input')
    expect(code.disabled).toBe(true)
    await fill('区域名称', '东营区（新）')
    await click('保存')
    expect(systemApi.updateDistrict).toHaveBeenCalledWith('d1', { name: '东营区（新）', expected_version: 3 }, expect.stringMatching(/^district-rename-/))
  })

  it('没有授权权限只能查看', async () => {
    await mount(DistrictsDialog, { visible: true, canEdit: false })
    expect(button('新增区域').disabled).toBe(true)
    expect(button('改名').disabled).toBe(true)
    expect(document.body.textContent).toContain('当前账号只能查看区域')
  })
})
