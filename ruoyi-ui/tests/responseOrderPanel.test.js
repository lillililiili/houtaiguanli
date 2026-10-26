import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import ResponseOrderPanel from '@/views/system/rules/ResponseOrderPanel.vue'
import { automationRuleApi } from '@/api/automationRules'

const permissions = vi.hoisted(() => ({ write: true }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => permissions.write }) }))
vi.mock('@/api/automationRules', () => ({ automationRuleApi: { responseOrder: vi.fn(), saveResponseOrder: vi.fn(), responseOrderHistory: vi.fn() } }))
vi.mock('@/views/system/rules/RuleAirspaceSelect.vue', () => ({ default: { template: '<div />' } }))
let app, host
const policy = () => ({ version: 8, enabled: false, minimum_risk: 'HIGH', prediction_enabled: true, observed_enabled: true, scope_mode: 'ALL', airspace_ids: [], airspace_names: [], schedule_mode: 'ALL_DAY', start_time: '00:00', end_time: '00:00', timezone: 'Asia/Shanghai', max_wait_seconds: 60, can_manage: true })
async function settle() { for (let i = 0; i < 30; i++) { await Promise.resolve(); await nextTick() } }
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(ResponseOrderPanel); app.use(ElementPlus); app.mount(host); await settle() }
function saveButton() { return [...host.querySelectorAll('button')].find(button => button.textContent.includes('保存处置顺序')) }
describe('处置顺序独立配置', () => {
  beforeEach(() => { vi.clearAllMocks(); permissions.write = true; automationRuleApi.responseOrder.mockResolvedValue(policy()); automationRuleApi.saveResponseOrder.mockResolvedValue({ ...policy(), version: 9, enabled: true }) })
  afterEach(() => { app?.unmount(); host?.remove(); document.body.innerHTML = '' })
  it('读取不会代开策略，保存携带版本与完整配置', async () => {
    await mount()
    expect(automationRuleApi.saveResponseOrder).not.toHaveBeenCalled()
    expect(host.textContent).toContain('预测及其衍生风险仍由人工核实')
    host.querySelector('.el-switch').click(); await settle()
    saveButton().click(); await settle()
    expect(automationRuleApi.saveResponseOrder).toHaveBeenCalledWith(expect.objectContaining({ expected_version: 8, enabled: true, max_wait_seconds: 60, prediction_enabled: true, observed_enabled: true }))
    saveButton().click(); await settle()
    expect(automationRuleApi.saveResponseOrder).toHaveBeenLastCalledWith(expect.objectContaining({ expected_version: 9 }))
  })
  it('无管理权限禁用保存，读取错误后不保留可编辑旧配置', async () => {
    permissions.write = false; await mount()
    expect(saveButton().disabled).toBe(true)
    expect(automationRuleApi.saveResponseOrder).not.toHaveBeenCalled()
  })
  it('并发版本冲突后重新读取，不自动重提', async () => {
    automationRuleApi.saveResponseOrder.mockRejectedValue({ status: 409, message: '版本已变更' })
    automationRuleApi.responseOrder.mockResolvedValueOnce(policy()).mockResolvedValue({ ...policy(), version: 10, max_wait_seconds: 45 })
    await mount(); saveButton().click(); await settle()
    expect(automationRuleApi.saveResponseOrder).toHaveBeenCalledTimes(1)
    expect(automationRuleApi.responseOrder).toHaveBeenCalledTimes(2)
    expect(host.querySelector('.el-input-number input').value).toBe('45')
    expect(host.textContent).toContain('已重新读取服务器配置')
  })
})
