import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import DirectoryRecordEditor from '@/views/system/organization/DirectoryRecordEditor.vue'
import NotificationSettingsView from '@/views/system/NotificationSettingsView.vue'
import { directoryApi } from '@/api/organizationDirectory'

vi.mock('@/api/organizationDirectory', () => ({ directoryApi: {
  options: vi.fn(), bindings: vi.fn(), saveContact: vi.fn(), saveProfile: vi.fn(), saveBinding: vi.fn(),
  settings: vi.fn(), setting: vi.fn(), diagnostics: vi.fn(), saveSetting: vi.fn()
} }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true }) }))
vi.mock('element-plus', async importOriginal => ({ ...await importOriginal(), ElMessage: { success: vi.fn() } }))
let app, host
const verifiedAt = Date.parse('2026-09-15T08:30:00Z')
const contact = { contact_id: 'contact-1', org_id: 'org-1', org_name: '关联单位', name: '已核实飞手', roles: ['PILOT'], phone: '13800000000', enabled: true, verified_at: verifiedAt, valid_until: null, verification_basis: '已核对本人资料', version: 4, notification_count: 1, pending_count: 2, history_count: 3 }
async function settle() { for (let index = 0; index < 12; index++) { await Promise.resolve(); await nextTick() } }
async function mount(component, props = {}) {
  host = document.createElement('div'); document.body.append(host)
  app = createApp(component, props); app.use(ElementPlus); app.mount(host); await settle()
}
const button = text => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === text)
beforeEach(() => {
  vi.clearAllMocks()
  directoryApi.options.mockResolvedValue({ items: [{ id: 'org-1', label: '关联单位' }], total: 1 })
  directoryApi.bindings.mockResolvedValue({ items: [], total: 0 })
  directoryApi.saveContact.mockResolvedValue({ ...contact, version: 5 })
  const risk = { setting_id: 'risk-superior', purpose: 'RISK_NOTICE', recipient_name: '上级', channel_type: 'NONE', enabled: false, endpoint_ref: '', version: 1, availability: 'UNAVAILABLE', blocked_reason: '配置停用', history_count: 0 }
  directoryApi.settings.mockResolvedValue({ items: [risk], total: 1 })
  directoryApi.setting.mockResolvedValue(risk)
  directoryApi.diagnostics.mockResolvedValue({ setting_id: risk.setting_id, availability: 'UNAVAILABLE', blocked_reason: '配置停用', pending_count: 0, history_count: 0, affected_plan_count: 0 })
})
afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })

describe('单位联系人及通知配置表单', () => {
  it('真实日期控件显示正确年份，保存保留核实时间且不强制账号', async () => {
    await mount(DirectoryRecordEditor, { visible: true, kind: 'contact', row: contact, canEdit: true })
    const dates = [...document.querySelectorAll('.el-date-editor input')]
    expect(dates.some(input => input.value.includes('2026'))).toBe(true)
    expect(dates.some(input => input.value.includes('1793'))).toBe(false)
    button('保存').click(); await settle()
    expect(directoryApi.saveContact).toHaveBeenCalledWith('contact-1', expect.objectContaining({ verified_at: verifiedAt, user_id: null, expected_version: 4, roles: ['PILOT'] }))
  })
  it('修改实际电话号码清除旧核验，不能把原号码的核验用于新号码', async () => {
    await mount(DirectoryRecordEditor, { visible: true, kind: 'contact', row: contact, canEdit: true })
    const phone = [...document.querySelectorAll('input')].find(input => input.value === contact.phone)
    phone.value = '13900000000'; phone.dispatchEvent(new Event('input', { bubbles: true })); await settle()
    expect(document.body.textContent).toContain('本次保存会清除原核验，请先保存新号码')
    expect([...document.querySelectorAll('.el-date-editor input')].every(input => !input.value)).toBe(true)
    button('保存').click(); await settle()
    expect(directoryApi.saveContact).toHaveBeenCalledWith('contact-1', expect.objectContaining({ phone: '13900000000', verified_at: null, verification_basis: '' }))
  })
  it('联系人版本冲突保留资料并阻止未核对的重复保存', async () => {
    directoryApi.saveContact.mockRejectedValue({ status: 409, message: '版本冲突' })
    await mount(DirectoryRecordEditor, { visible: true, kind: 'contact', row: contact, canEdit: true })
    button('保存').click(); await settle()
    expect(document.body.textContent).toContain('本次未覆盖现有资料')
    expect(button('保存')).toBeUndefined()
    expect(button('重新加载并核对')).toBeDefined()
    expect(directoryApi.saveContact).toHaveBeenCalledTimes(1)
  })
  it('只读联系人可以查看历史影响，不展示保存操作', async () => {
    await mount(DirectoryRecordEditor, { visible: true, kind: 'contact', row: contact, canEdit: false })
    expect(document.body.textContent).toContain('待发任务 2 项')
    expect(document.body.textContent).toContain('历史通知 3 项')
    expect(button('保存')).toBeUndefined()
  })
  it('通知上级不出现接收单位或联系人配置，诊断不触发写请求', async () => {
    await mount(NotificationSettingsView)
    expect(host.textContent).toContain('风险通知统一通知上级')
    const labels = [...host.querySelectorAll('.settings-detail .el-form-item__label')].map(item => item.textContent)
    expect(labels.some(label => /接收单位|联系人|区域|风险类型/.test(label))).toBe(false)
    expect(host.textContent).toContain('当前不可用')
    button('校验已保存配置').click(); await settle()
    expect(directoryApi.diagnostics).toHaveBeenCalledTimes(2)
    expect(directoryApi.saveSetting).not.toHaveBeenCalled()
  })
})
